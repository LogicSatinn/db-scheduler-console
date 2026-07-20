package io.github.logicsatinn.dbscheduler.console.service;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

/** Creates a counter-backed reporter when an application provides Micrometer. */
public final class MicrometerHistoryFailureReporter {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerHistoryFailureReporter.class);
    private static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";

    private MicrometerHistoryFailureReporter() { }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static HistoryFailureReporter create(ListableBeanFactory beans) {
        if (!ClassUtils.isPresent(METER_REGISTRY, MicrometerHistoryFailureReporter.class.getClassLoader())) {
            return HistoryFailureReporter.NOOP;
        }
        try {
            Class<?> registryType = ClassUtils.forName(
                    METER_REGISTRY, MicrometerHistoryFailureReporter.class.getClassLoader());
            Object registry = beans.getBeanProvider(ResolvableType.forClass(registryType)).getIfAvailable();
            if (registry == null) {
                return HistoryFailureReporter.NOOP;
            }
            Method counterFactory = registryType.getMethod("counter", String.class, String[].class);
            Object counter = counterFactory.invoke(registry,
                    "db.scheduler.console.history.write.failures", (Object) new String[0]);
            Method increment = counter.getClass().getMethod("increment");
            return failure -> {
                try {
                    increment.invoke(counter);
                } catch (ReflectiveOperationException reflectionFailure) {
                    LOG.debug("Unable to increment db-scheduler-console history failure counter",
                            reflectionFailure);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("Micrometer is present but the history failure counter could not be created", e);
            return HistoryFailureReporter.NOOP;
        }
    }
}
