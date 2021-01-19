module havis.util.core.common {
    requires jackson.core;
    requires jackson.databind;
    requires jaxb.api;

    requires transitive havis.util.core.api;
    requires transitive jackson.annotations;
    requires transitive java.logging;
    requires transitive java.rmi;
    requires transitive java.sql;

    exports havis.util.core.common;
    exports havis.util.core.common.app;
    exports havis.util.core.common.license;
    exports havis.util.core.common.log;
    exports havis.util.core.common.rmi;

}