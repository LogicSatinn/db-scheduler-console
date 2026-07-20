package io.github.logicsatinn.dbscheduler.console.data;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class Stacktraces {

    private Stacktraces() { }

    public static String render(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
