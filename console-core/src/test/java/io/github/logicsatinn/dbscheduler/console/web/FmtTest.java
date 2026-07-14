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

    @Test
    void urlEncodesQueryStringValues() {
        assertThat(Fmt.url("order-1")).isEqualTo("order-1");
        assertThat(Fmt.url("order+A&B")).isEqualTo("order%2BA%26B");
        assertThat(Fmt.url("order A")).isEqualTo("order%20A");
        assertThat(Fmt.url("a/b?c=d#e")).isEqualTo("a%2Fb%3Fc%3Dd%23e");
        assertThat(Fmt.url(null)).isEmpty();
    }
}
