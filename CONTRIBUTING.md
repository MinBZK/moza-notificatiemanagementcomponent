# Bijdragen aan de NotificatieManagementComponent

Bedankt voor je interesse in bijdragen aan dit project. Dit project is onderdeel van het MOZa-programma van het Ministerie van Binnenlandse Zaken en Koninkrijksrelaties.

## Hoe bij te dragen

### Issues melden

Gebruik de [issue templates](.github/ISSUE_TEMPLATE/) voor het melden van bugs of het aanvragen van nieuwe functionaliteit.

### Pull requests

1. Fork de repository en maak een feature branch aan vanaf `main`.
2. Zorg dat de code compileert en alle tests slagen: `./mvnw verify`.
3. Maak een pull request aan met een duidelijke beschrijving van de wijziging en de motivatie.
4. Gebruik de [pull request template](.github/PULL_REQUEST_TEMPLATE.md).

### Codestijl

- Java 25, Quarkus 3.x
- Bestaande conventies in de codebase volgen
- Nederlandstalige naamgeving voor domeinconcepten, Engels voor generieke technische concepten

De volledige werkregels staan in [`AGENTS.md`](AGENTS.md), de regels per onderdeel in
[`.claude/rules/`](.claude/rules/) en de werking van de component in
[`docs/architectuur.md`](docs/architectuur.md). Ze gelden voor iedereen, met of zonder AI.

### Werken met Claude Code

De repo bevat een gedeelde Claude Code-setup in `.claude/`. Eenmalig per machine:

1. Gebruik Claude Code v2.1.277 of hoger. Oudere versies lezen `AGENTS.md` niet.
2. Vertrouw de projectmap als Claude Code daarom vraagt. Pas daarna gelden de gedeelde
   permissies en de plugin-marketplace.
3. Installeer de gedeelde plugins. `.claude/settings.json` zet ze aan, maar downloadt ze niet:

   ```bash
   claude plugin install standaarden@overheid-plugins --scope project
   claude plugin install developer-overheid@overheid-plugins --scope project
   claude plugin install nerds@overheid-plugins --scope project
   claude plugin install zad-actions@overheid-plugins --scope project
   ```

Wat je daarna krijgt:

- **Hooks** die een wijziging aan gegenereerde code of een toegepaste migratie tegenhouden,
  waarschuwen bij `now()` zonder `Clock`, en `./mvnw verify` draaien vóór een `git push`.
  Java komt uit SDKMAN (`source "$HOME/.sdkman/bin/sdkman-init.sh"`); zonder Java slaat de
  push-check over en vangt CI het af.
- **`/nmc-review <PR-nummer>`**: review met de projectchecklist, en de fixes als PR bovenop die
  PR in plaats van losse comments.
- **De agent `nmc-reviewer`** voor een onafhankelijke review van je eigen branch.

Persoonlijke instellingen horen in `.claude/settings.local.json`; die wordt niet ingecheckt.

## Gedragscode

Dit project hanteert een [gedragscode](CODE_OF_CONDUCT.md). Door bij te dragen ga je akkoord met de daarin beschreven normen.

## Licentie

Bijdragen worden beschikbaar gesteld onder de [EUPL-1.2](LICENSE) licentie.

## Contact

Vragen? Neem contact op via [moza@minbzk.nl](mailto:moza@minbzk.nl) of open een issue.
