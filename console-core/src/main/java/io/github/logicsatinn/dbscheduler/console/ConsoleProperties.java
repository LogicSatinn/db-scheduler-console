package io.github.logicsatinn.dbscheduler.console;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "db-scheduler-console")
public class ConsoleProperties {

    private boolean enabled = true;
    private String basePath = "/db-scheduler-console";
    private boolean readOnly = false;
    private Duration pollingInterval = Duration.ofSeconds(5);
    private final History history = new History();
    private final TaskData taskData = new TaskData();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public Duration getPollingInterval() { return pollingInterval; }
    public void setPollingInterval(Duration pollingInterval) { this.pollingInterval = pollingInterval; }
    public History getHistory() { return history; }
    public TaskData getTaskData() { return taskData; }

    public static class History {
        private boolean enabled = true;
        private boolean storeTaskData = true;
        private Duration retention = Duration.ofDays(14);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isStoreTaskData() { return storeTaskData; }
        public void setStoreTaskData(boolean storeTaskData) { this.storeTaskData = storeTaskData; }
        public Duration getRetention() { return retention; }
        public void setRetention(Duration retention) { this.retention = retention; }
    }

    public static class TaskData {
        private boolean visible = true;

        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
    }
}
