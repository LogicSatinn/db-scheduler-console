package io.github.logicsatinn.dbscheduler.console.data;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public record InstanceKey(String taskName, String instanceId) {

    public static String matching(Collection<InstanceKey> keys, List<Object> params) {
        for (InstanceKey key : keys) {
            params.add(key.taskName());
            params.add(key.instanceId());
        }
        return "(" + String.join(" OR ",
                Collections.nCopies(keys.size(), "(task_name = ? AND task_instance = ?)")) + ")";
    }
}
