-- Het berichttype bepaalt het verzendbeleid (wachttijd voor de herverzending); geldig_tot begrenst het
-- starten van een poging. Rijen van vóór deze migratie hebben geen grens en volgen het standaardbeleid.
ALTER TABLE notificatie
    ADD COLUMN bericht_type varchar(64),
    ADD COLUMN geldig_tot timestamp(6) with time zone;
