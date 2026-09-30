-- Het tijdstip uit de delivered-receipt waarop bezorgd rust. Een latere faalreceipt overschrijft
-- receipt_tijdstip, dit niet; de vaststellingstermijn loopt vanaf hier.
ALTER TABLE poging ADD COLUMN bezorgd_op timestamp(6) with time zone;

UPDATE poging SET bezorgd_op = receipt_tijdstip WHERE status = 'BEZORGD';

-- Open vaststeltaken die de controletaak plande voordat de termijn bestond, staan op het moment van
-- plannen en zouden direct vaststellen. De controletaak plant ze opnieuw vanaf bezorgd_op.
DELETE FROM taak WHERE soort = 'BEZORGING_VASTSTELLEN' AND status = 'OPEN';
