package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FmtTest {

    @Test
    void formatsDurations() {
        assertThat(Fmt.duration(450)).isEqualTo("450 ms");
        assertThat(Fmt.duration(2300)).isEqualTo("2.3 s");
        assertThat(Fmt.duration(72000)).isEqualTo("1 m 12 s");
    }

    @Test
    void nullInstantIsDash() {
        assertThat(Fmt.ts(null)).isEqualTo("—");
    }
}
