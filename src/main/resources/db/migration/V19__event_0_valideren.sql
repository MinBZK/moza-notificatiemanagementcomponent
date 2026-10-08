-- Stap 2 van 3: VALIDATE leest de hele default-partitie, maar houdt alleen een slot dat lezen en
-- schrijven toestaat. De timeouts van de rol gaan voor deze transactie uit.
SET LOCAL statement_timeout = 0;
SET LOCAL transaction_timeout = 0;

ALTER TABLE event_standaard VALIDATE CONSTRAINT event_0_bereik;
