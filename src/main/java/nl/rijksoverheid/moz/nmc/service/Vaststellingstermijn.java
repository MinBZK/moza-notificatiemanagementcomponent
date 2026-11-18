package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.config.VaststellingConfig;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * De termijn uit de MEBV waarbinnen een {@code delivered} nog door een faalreceipt herroepen kan
 * worden. De vaststeltaak wacht daarnaast het callback-venster van NotifyNL af, zodat een faalreceipt
 * met een tijdstip binnen de termijn die taak niet kan kruisen.
 */
@ApplicationScoped
public class Vaststellingstermijn {

    private final Duration termijn;
    private final Duration callbackVenster;

    public Vaststellingstermijn(VaststellingConfig config) {
        this.termijn = config.termijn();
        this.callbackVenster = config.callbackVenster();
    }

    /** Of een receipt met dit tijdstip nog binnen de termijn na de bezorging valt. */
    public boolean binnen(OffsetDateTime bezorgdOp, OffsetDateTime tijdstip) {
        Objects.requireNonNull(bezorgdOp, "bezorgdOp is verplicht");
        Objects.requireNonNull(tijdstip, "tijdstip is verplicht");

        return !tijdstip.isAfter(bezorgdOp.plus(termijn));
    }

    /** Wanneer de vaststeltaak voor een bezorging op {@code bezorgdOp} aan de beurt is. */
    public OffsetDateTime vaststellenOp(OffsetDateTime bezorgdOp) {
        Objects.requireNonNull(bezorgdOp, "bezorgdOp is verplicht");

        return bezorgdOp.plus(termijn).plus(callbackVenster);
    }
}
