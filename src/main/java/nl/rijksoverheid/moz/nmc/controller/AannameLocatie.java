package nl.rijksoverheid.moz.nmc.controller;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;
import java.util.UUID;

/** Het id van de notificatie die deze aanroep heeft aangenomen, voor de {@code Location}-header. */
@RequestScoped
public class AannameLocatie {

    static final String PAD = "/api/nmc/v1/notificaties/";

    private UUID notificatieId;

    public void zet(UUID notificatieId) {
        this.notificatieId = notificatieId;
    }

    Optional<String> pad() {
        return Optional.ofNullable(notificatieId).map(id -> PAD + id);
    }
}
