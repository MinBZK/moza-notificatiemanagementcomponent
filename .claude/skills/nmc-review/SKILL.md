---
name: nmc-review
description: Review een PR van de NMC met de projectchecklist en lever de fixes als PR bovenop die PR in plaats van als losse comments. Neemt ook open reviewcomments van anderen mee. Zonder PR-nummer een review van de eigen branch, alleen als rapport.
argument-hint: "[PR-nummer] [--alleen-rapport]"
disable-model-invocation: true
---

# /nmc-review

Argumenten: `$ARGUMENTS`

Het doel is tempo zonder in te leveren op kwaliteit: de auteur krijgt een diff die hij kan
mergen en een korte lijst met wat bewust níet is aangepast, geen stapel comments.

## 1. Bepaal wat er gereviewd wordt

- **Geen PR-nummer**: review de eigen branch tegen zijn basis (`git diff origin/main...HEAD`,
  of de basis van de open PR van deze branch). Lever alleen het rapport (stap 3) en stop.
- **Met PR-nummer**: haal de PR op met
  `gh pr view <nummer> --json number,title,url,headRefName,baseRefName,body,author`.
- **`--alleen-rapport`**: doe stap 2 en 3 en stop daarna.

## 2. Verzamel de bevindingen

1. Haal de branch op zonder de werkboom van de gebruiker te raken:
   `git fetch origin <headRefName> <baseRefName>` en maak een worktree naast de repo:
   `git worktree add ../<repo>-review-<nummer> -b <headRefName>-review origin/<headRefName>`.
   De branchnaam begint zo met hetzelfde issuenummer als de PR. Bestaat die branch al van een
   eerdere run, kies dan een volgnummer (`-review-2`). Komt de PR uit een fork, haal hem dan op
   met `git fetch origin pull/<nummer>/head`.
2. Laat de agent `nmc-reviewer` de wijziging `origin/<baseRefName>...origin/<headRefName>`
   reviewen, met de worktree als werkmap. Beoordeel zijn rapport kritisch: een bevinding zonder
   scenario of regelnummer valt af.
3. Haal de open reviewcomments van anderen op:
   `gh api repos/{owner}/{repo}/pulls/<nummer>/comments --paginate` en de reviews zelf
   (`.../reviews`). Neem alleen comments mee waar nog niet op gereageerd is of die nog niet
   verwerkt zijn. Beoordeel ze met dezelfde maatstaf als de bevindingen van de agent: een
   comment die tegen een afspraak in `AGENTS.md` ingaat, is `overslaan`, met de reden.

## 3. Deel in en leg voor

Maak één tabel van alle bevindingen en comments, gesorteerd op gewicht:

| # | Gewicht | Afhandeling | Bron | Waar | Wat |
|---|---------|-------------|------|------|-----|

- **Gewicht**: `blocker`, `moet` of `nit`.
- **Afhandeling**: `fixen` (in de vervolg-PR), `issue` (vervolgissue), `ba-po` (functionele keuze
  voor BA/PO) of `overslaan` (nice-to-have, of in strijd met een afspraak). Nice-to-have sla je
  over; daar kom je toch nooit aan toe.
- **Bron**: `review` of de naam van de commenter.

Leg de tabel voor aan de gebruiker en **wacht op akkoord** voordat je iets wijzigt. De gebruiker
kan afhandelingen omzetten.

## 4. Fix

Werk in de worktree.

- Eén commit per samenhangend onderwerp, met een korte Nederlandse commitmessage.
- Pas de regels uit `AGENTS.md` en `.claude/rules/` toe; een fix zonder test voor het gedrag dat
  hij herstelt, is niet af.
- Kun je een fix niet verantwoord maken (te groot, onduidelijk wat de bedoeling is), zet hem dan
  om naar `issue` of `ba-po` en meld dat.
- Draai `./mvnw verify` in de worktree. Faalt hij, los het op of meld het; push niet op rood.
  De push-hook verifieert de worktree ook, en slaat over als verify op dezelfde commit al slaagde.

## 5. Lever op

Vraag akkoord voor elke stap die naar GitHub schrijft.

1. Push de branch en open een PR **naar `<headRefName>`**, niet naar `main`, met de PR-template.
   Neem in de beschrijving op:
   - een verwijzing naar de PR die verbeterd wordt, en naar het issue voluit
     (`MinBZK/MijnOverheidZakelijk#<nummer>`);
   - per fix: wat en waarom, met het nummer uit de tabel;
   - de punten die níet zijn aangepast, per afhandeling (`issue`, `ba-po`, `overslaan`), met de
     reden;
   - voor elk reviewcomment dat verwerkt is: een link naar dat comment.
2. Stel voor elk `issue`- en `ba-po`-punt een concept-issue op (titel met `NMC:` vooraan,
   probleem, scenario, voorstel). **Maak de issues niet zelf aan**; geef ze aan de gebruiker.
3. Reageer niet automatisch op comments van anderen. Bied aan om per verwerkt comment een korte
   reactie met een link naar de vervolg-PR te plaatsen.
4. Laat de worktree staan tot de gebruiker hem niet meer nodig heeft; ruim op met
   `git worktree remove`.
