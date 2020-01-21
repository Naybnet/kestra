package org.kestra.core.runners;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.micronaut.context.ApplicationContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.kestra.core.models.executions.Execution;
import org.kestra.core.models.executions.LogEntry;
import org.kestra.core.models.executions.MetricEntry;
import org.kestra.core.models.executions.TaskRun;
import org.kestra.core.models.flows.Flow;
import org.kestra.core.models.tasks.ResolvedTask;
import org.kestra.core.serializers.JacksonMapper;
import org.kestra.core.storages.StorageInterface;
import org.kestra.core.storages.StorageObject;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@NoArgsConstructor
public class RunContext {
    private static VariableRenderer variableRenderer = new VariableRenderer();
    private ApplicationContext applicationContext;
    private StorageInterface storageInterface;
    private URI storageOutputPrefix;
    private String envPrefix;
    private Map<String, Object> variables;
    private List<MetricEntry> metrics;
    private ContextAppender contextAppender;
    private String loggerName;
    private Logger logger;

    public RunContext(ApplicationContext applicationContext, Flow flow, Execution execution) {
        this.applicationContext = applicationContext;
        this.storageInterface = applicationContext.findBean(StorageInterface.class).orElseThrow();
        this.envPrefix = applicationContext.getProperty("kestra.variables.env-vars-prefix", String.class, "KESTRA_");

        this.variables = this.taskRunVariables(flow, null, execution, null);
        this.loggerName = "flow." + flow.getId();
    }

    public RunContext(ApplicationContext applicationContext, Flow flow, ResolvedTask task, Execution execution, TaskRun taskRun) {
        this.applicationContext = applicationContext;
        this.storageInterface = applicationContext.findBean(StorageInterface.class).orElseThrow();
        this.envPrefix = applicationContext.getProperty("kestra.variables.env-vars-prefix", String.class, "KESTRA_");

        this.storageOutputPrefix = StorageInterface.outputPrefix(flow, task, execution, taskRun);
        this.variables = this.taskRunVariables(flow, task, execution, taskRun);
        this.loggerName = "flow." + flow.getId() + "." + taskRun.getTaskId();
    }

    public List<MetricEntry> getMetrics() {
        return metrics;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    @JsonIgnore
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    private Map<String, Object> taskRunVariables(Flow flow, ResolvedTask resolvedTask, Execution execution, TaskRun taskRun) {
        ImmutableMap.Builder<String, Object> builder = ImmutableMap.<String, Object>builder()
            .put("envs", envVariables());

        if (applicationContext.getProperties("kestra.variables.globals").size() > 0) {
            builder.put("globals", applicationContext.getProperties("kestra.variables.globals"));
        }

        if (resolvedTask != null && flow.isListenerTask(resolvedTask.getTask().getId())) {
            builder
                .put("flow", JacksonMapper.toMap(flow))
                .put("execution", JacksonMapper.toMap(execution));

        } else {
            builder
                .put("flow", ImmutableMap.of(
                    "id", flow.getId(),
                    "namespace", flow.getNamespace()
                ))
                .put("execution", ImmutableMap.of(
                    "id", execution.getId(),
                    "startDate", execution.getState().getStartDate()
                ));
        }

        if (resolvedTask != null) {
            builder
                .put("task", ImmutableMap.of(
                    "id", resolvedTask.getTask().getId(),
                    "type", resolvedTask.getTask().getType()
                ));
        }

        if (taskRun != null) {
            builder.put("taskrun", this.variables(taskRun));
        }

        if (execution.getTaskRunList() != null) {
            builder.put("outputs", execution.outputs());
        }

        if (execution.getInputs() != null) {
            builder.put("inputs", execution.getInputs());
        }

        if (flow.getVariables() != null) {
            builder.put("vars", flow.getVariables());
        }

        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, String> envVariables() {
        Map<String, String> result = new HashMap<>(System.getenv());
        result.putAll((Map) System.getProperties());

        return result
            .entrySet()
            .stream()
            .filter(e -> e.getKey().startsWith(this.envPrefix))
            .map(e -> new AbstractMap.SimpleEntry<>(
                e.getKey().substring(this.envPrefix.length()).toLowerCase(),
                e.getValue()
            ))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, Object> variables(TaskRun taskRun) {
        ImmutableMap.Builder<String, Object> builder = ImmutableMap.<String, Object>builder()
            .put("id", taskRun.getId())
            .put("startDate", taskRun.getState().getStartDate())
            .put("attemptsCount", taskRun.getAttempts() == null ? 0 : taskRun.getAttempts().size());

        if (taskRun.getParentTaskRunId() != null) {
            builder.put("parentId", taskRun.getParentTaskRunId());
        }

        if (taskRun.getValue() != null) {
            builder.put("value", taskRun.getValue());
        }

        return builder.build();
    }

    @SneakyThrows
    private Map<String, Object> resolveObjectStorage(Map<String, Object> variables) {
        return variables
            .entrySet()
            .stream()
            .map(r -> {
                if (r.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) r.getValue();

                    if (map.containsKey("type") && map.get("type").equals(StorageObject.class.getName())) {
                        r.setValue(new StorageObject(
                            this.storageInterface,
                            URI.create((String) map.get("uri"))
                        ));
                    } else {
                        r.setValue(resolveObjectStorage(map));
                    }
                }

                return r;
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public RunContext forWorker(ApplicationContext applicationContext, TaskRun taskRun) {
        this.applicationContext = applicationContext;
        this.storageInterface = applicationContext.findBean(StorageInterface.class).orElseThrow();

        HashMap<String, Object> clone = new HashMap<>(this.variables);

        clone.remove("taskrun");
        clone.put("taskrun", this.variables(taskRun));

        if (variables.containsKey("inputs")) {
            Map<String, Object> inputs = resolveObjectStorage((Map<String, Object>) variables.get("inputs"));
            clone.remove("inputs");
            clone.put("inputs", inputs);
        }

        if (variables.containsKey("outputs")) {
            Map<String, Object> outputs = resolveObjectStorage((Map<String, Object>) variables.get("outputs"));
            clone.remove("outputs");
            clone.put("outputs", outputs);
        }

        this.variables = ImmutableMap.copyOf(clone);

        return this;
    }

    @VisibleForTesting
    public RunContext(ApplicationContext applicationContext, Map<String, Object> variables) {
        this.applicationContext = applicationContext;
        this.storageInterface = applicationContext.findBean(StorageInterface.class).orElse(null);
        this.envPrefix = applicationContext.getProperty("kestra.variables.env-vars-prefix", String.class, "KESTRA_");

        this.storageOutputPrefix = URI.create("");
        this.variables = variables;
        this.loggerName = "flow.unitest";
    }

    public org.slf4j.Logger logger(Class<?> cls) {
        if (this.logger == null) {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            this.logger = loggerContext.getLogger(this.loggerName != null ? loggerName : cls.getName());

            this.contextAppender = new ContextAppender();
            this.contextAppender.setContext(loggerContext);
            this.contextAppender.start();

            this.logger.addAppender(this.contextAppender);
            this.logger.setLevel(Level.TRACE);
            this.logger.setAdditive(true);
        }

        return this.logger;
    }

    public String render(String inline) throws IOException {
        return variableRenderer.render(inline, this.variables);
    }

    public List<String> render(List<String> inline) throws IOException {
        return variableRenderer.render(inline, this.variables);
    }

    public String render(String inline, Map<String, Object> variables) throws IOException {
        return variableRenderer.render(
            inline,
            Stream
                .concat(this.variables.entrySet().stream(), variables.entrySet().stream())
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
                ))
        );
    }

    public List<LogEntry> logs() {
        if (this.contextAppender == null) {
            return new ArrayList<>();
        }

        return this.contextAppender
            .events
            .stream()
            .map(event -> LogEntry.builder()
                .level(org.slf4j.event.Level.valueOf(event.getLevel().toString()))
                .message(event.getFormattedMessage())
                .timestamp(Instant.ofEpochMilli(event.getTimeStamp()))
                .thread(event.getThreadName())
                .build()
            )
            .collect(Collectors.toList());
    }

    public InputStream uriToInputStream(URI uri) throws FileNotFoundException {
        if (uri.getScheme().equals("kestra")) {
            return this.storageInterface.get(uri);
        }

        if (uri.getScheme().equals("file")) {
            return new FileInputStream(uri.toString());
        }

        if (uri.getScheme().equals("http")) {
            try {
                return uri.toURL().openStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        throw new IllegalArgumentException("Invalid scheme for uri '" + uri + "'");
    }

    public StorageObject putFile(File file) throws IOException {
        URI uri = URI.create(this.storageOutputPrefix.toString());
        URI resolve = uri.resolve(uri.getPath() + "/" + file.getName());

        return this.storageInterface.put(resolve, new FileInputStream(file));
    }

    public List<MetricEntry> metrics() {
        return this.metrics;
    }

    public static class ContextAppender extends AppenderBase<ILoggingEvent> {
        private final ConcurrentLinkedQueue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

        @Override
        public void start() {
            super.start();
        }

        @Override
        public void stop() {
            super.stop();
            events.clear();
        }

        @Override
        protected void append(ILoggingEvent e) {
            events.add(e);
        }
    }
}