package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class HtmxAssertions {

    private HtmxAssertions() {}

    /**
     * htmx inherits hx-select from ancestors. An action form inside a refreshable region would
     * otherwise filter its flash response through the region's selector and swap in nothing.
     */
    static void actionFormsDoNotInheritRegionSelect(Document doc) {
        var forms = doc.select("form[hx-post]");
        assertThat(forms).as("action forms").isNotEmpty();
        for (Element form : forms) {
            for (Element ancestor : form.parents()) {
                if (ancestor.hasAttr("hx-select")) {
                    assertThat(Arrays.asList(ancestor.attr("hx-disinherit").split("\\s+")))
                            .as("%s inherits hx-select from %s", form.attr("hx-post"),
                                    ancestor.cssSelector())
                            .containsAnyOf("hx-select", "*");
                    break;
                }
            }
        }
    }
}
