package nl.rijksoverheid.moz.nmc.repository;

import nl.rijksoverheid.moz.nmc.domain.Event;

/** Een event met de transactie-id waaronder het is geschreven; de entity mapt die kolom niet. */
public record EventMetXid(long xid, Event event) {
}
