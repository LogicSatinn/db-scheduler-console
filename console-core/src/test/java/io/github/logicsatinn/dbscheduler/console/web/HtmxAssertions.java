package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class HtmxAssertions {

    private HtmxAssertions() {}

    static void actionFormsDoNotInheritRegionSelect(Document doc) {
        var forms = doc.select("form[hx-post]");
        assertThat(forms).as("action forms").isNotEmpty();
        for (Element form : forms) {
            for (Element ancestor : form.parents()) {
                assertThat(ancestor.attributes().asList())
                        .as("%s inherits hx-select from %s", form.attr("hx-post"), ancestor.cssSelector())
                        .noneMatch(attribute -> attribute.getKey().startsWith("hx-select:inherited"));
            }
        }
    }
}
