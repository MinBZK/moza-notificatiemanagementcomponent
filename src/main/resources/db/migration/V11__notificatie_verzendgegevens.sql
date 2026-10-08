-- V11: de verzendgegevens (template, regie, dienstverlener en dienst voor de Profielservice-lookup)
-- staan op de notificatie en niet alleen in de payload van de eerste verzendtaak, zodat elke
-- verzendtaak kan versturen, ook een die de controletaak opnieuw plant. V10 hoort bij de eventfeed.

ALTER TABLE notificatie
    ADD COLUMN template_id varchar(64),
    ADD COLUMN regie varchar(16) CHECK (regie IN ('CENTRAAL', 'DECENTRAAL')),
    ADD COLUMN dienstverlener_naam varchar(255),
    ADD COLUMN dienst varchar(255);

UPDATE notificatie n
   SET template_id = t.payload ->> 'templateId',
       regie = t.payload ->> 'regie',
       dienstverlener_naam = t.payload ->> 'dienstverlener',
       dienst = t.payload ->> 'dienst'
  FROM taak t
 WHERE t.soort = 'VERZENDEN'
   AND t.notificatie_id = n.id
   AND n.template_id IS NULL;
