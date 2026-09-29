-- Wat er in het tijdvak nog over is van het vaste aandeel van de navraag. Verzenden laat het staan,
-- de navraag neemt alleen hieruit, zodat geen van beide de ander verdringt.
ALTER TABLE verzendbudget ADD COLUMN navraag_tokens integer NOT NULL DEFAULT 0;
ALTER TABLE verzendbudget ADD CONSTRAINT verzendbudget_navraag_tokens_check
    CHECK (navraag_tokens >= 0 AND navraag_tokens <= tokens);
