package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * De termijn uit de MEBV waarbinnen een {@code delivered} nog door een faalreceipt herroepen kan
 * worden. De vaststeltaak wacht daarnaast het callback-venster van NotifyNL af, zodat een faalreceipt
 * met een tijdstip binnen de termijn die taak niet kan kruisen.
 */
@ApplicationScoped
public class Vaststellingstermijn {

    private final Duration termijn;
    private final Duration callbackVenster;

    public Vaststellingstermijn(@ConfigProperty(name = "nmc.vaststelling.termijn") Duration termijn,
                                @ConfigProperty(name = "nmc.vaststelling.callback-venster") Duration callbackVenster) {
        this.termijn = termijn;
        this.callbackVenster = callbackVenster;
    }

    /** Of een receipt met dit tijdstip nog binnen de termijn na de bezorging valt. */
    public boolean binnen(OffsetDateTime bezorgdOp, OffsetDateTime tijdstip) {
        return !tijdstip.isAfter(bezorgdOp.plus(termijn));
    }

    /** Wanneer de vaststeltaak voor een bezorging op {@code bezorgdOp} aan de beurt is. */
    public OffsetDateTime vaststellenOp(OffsetDateTime bezorgdOp) {
        return bezorgdOp.plus(termijn).plus(callbackVenster);
    }
}
