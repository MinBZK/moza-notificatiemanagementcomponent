-- Wat er in het tijdvak nog over is van de reservering van het verzenden en van de navraag. Elk laat
-- de reservering van de ander staan, zodat geen van beide de ander verdringt; de rest van de tokens is
-- voor wie het eerst claimt.
ALTER TABLE verzendbudget ADD COLUMN verzend_tokens integer NOT NULL DEFAULT 0;
ALTER TABLE verzendbudget ADD COLUMN navraag_tokens integer NOT NULL DEFAULT 0;
ALTER TABLE verzendbudget ADD CONSTRAINT verzendbudget_reserveringen_check
    CHECK (verzend_tokens >= 0 AND navraag_tokens >= 0 AND verzend_tokens + navraag_tokens <= tokens);

-- Het tijdstip uit de delivered-receipt waarop bezorgd rust. Een latere faalreceipt overschrijft
-- receipt_tijdstip, dit niet; de vaststellingstermijn loopt vanaf hier.
ALTER TABLE poging ADD COLUMN bezorgd_op timestamp(6) with time zone;

UPDATE poging SET bezorgd_op = receipt_tijdstip WHERE status = 'BEZORGD';

-- De laatste status zoals NotifyNL hem letterlijk gaf, uit een receipt of een navraag; ook een status
-- die het NMC niet kent, zodat te zien is wat NotifyNL meldde voordat de poging op onbekend eindigde.
ALTER TABLE poging ADD COLUMN laatste_notify_status varchar(64);

-- Open vaststeltaken die de controletaak plande voordat de termijn bestond, staan op het moment van
-- plannen en zouden direct vaststellen. De controletaak plant ze opnieuw vanaf bezorgd_op.
DELETE FROM taak WHERE soort = 'BEZORGING_VASTSTELLEN' AND status = 'OPEN';
