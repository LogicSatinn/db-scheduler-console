package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Precomputed SVG geometry for the 24h throughput chart (viewBox 0 0 720 170). */
public final class ChartVm {

    static final double PLOT_LEFT = 30;
    static final double PLOT_RIGHT = 715;
    static final double BASELINE = 140;
    static final double PLOT_TOP = 12;
    static final double BAR_WIDTH = 22;
    static final double SEGMENT_GAP = 2;

    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private ChartVm() {}

    public record Bar(double x, double width, double ySucceeded, double heightSucceeded,
                      double yFailed, double heightFailed, String tooltip) {}

    public record Axis(double y, String label) {}

    public record Label(double x, String text) {}

    public record Model(List<Bar> bars, List<Axis> gridLines, List<Label> xLabels, boolean empty) {}

    public static Model of(List<StatsService.HourBucket> buckets) {
        if (buckets.isEmpty()) {
            return new Model(List.of(), List.of(), List.of(), true);
        }
        long max = Math.max(1, buckets.stream()
                .mapToLong(b -> b.succeeded() + b.failed()).max().orElse(1));
        double plotHeight = BASELINE - PLOT_TOP;
        double slot = (PLOT_RIGHT - PLOT_LEFT) / buckets.size();

        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            var b = buckets.get(i);
            double x = PLOT_LEFT + i * slot + (slot - BAR_WIDTH) / 2;
            double hOk = b.succeeded() * plotHeight / max;
            double hFail = b.failed() * plotHeight / max;
            double yOk = BASELINE - hOk;
            double yFail = yOk - (hFail > 0 && hOk > 0 ? SEGMENT_GAP : 0) - hFail;
            String hour = HOUR.format(b.hourStart());
            bars.add(new Bar(x, BAR_WIDTH, yOk, hOk, yFail, hFail,
                    hour + " · " + b.succeeded() + " succeeded, " + b.failed() + " failed"));
        }

        List<Axis> grid = List.of(
                new Axis(BASELINE, "0"),
                new Axis(BASELINE - plotHeight / 2, String.valueOf(Math.round(max / 2.0))),
                new Axis(PLOT_TOP, String.valueOf(max)));

        List<Label> xLabels = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i += 6) {
            xLabels.add(new Label(PLOT_LEFT + i * slot + slot / 2,
                    HOUR.format(buckets.get(i).hourStart())));
        }
        return new Model(bars, grid, xLabels, false);
    }
}
