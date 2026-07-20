package nl.rijksoverheid.moz.nmc.controller;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.Logboek;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;

import java.lang.reflect.Method;

@Provider
public class LogboekContextFilter implements ContainerRequestFilter {

    // Placeholder zodat de @Logboek-interceptor altijd een niet-lege data_subject_id/-type
    // heeft, ook als Bean Validation de aanvraag afwijst vóórdat de controller-methode
    // (die de echte waarde zet) ooit draait.
    private static final String ONBEKENDE_BETROKKENE_ID = "onbekend";
    private static final String ONBEKEND_BETROKKENE_TYPE = "ONBEKEND";

    private final LogboekContext logboekContext;
    private final ResourceInfo resourceInfo;

    public LogboekContextFilter(LogboekContext logboekContext, @Context ResourceInfo resourceInfo) {
        this.logboekContext = logboekContext;
        this.resourceInfo = resourceInfo;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceMethod != null && resourceMethod.isAnnotationPresent(Logboek.class)) {
            logboekContext.setDataSubjectId(ONBEKENDE_BETROKKENE_ID);
            logboekContext.setDataSubjectType(ONBEKEND_BETROKKENE_TYPE);
        }
    }
}
