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
   permissies en de plugin-marketplaces.
3. Installeer de hulpprogramma's en de plugins. Op macOS:

   ```bash
   brew install jq jdtls jbang

   for plugin in \
       standaarden@overheid-plugins \
       developer-overheid@overheid-plugins \
       nerds@overheid-plugins \
       zad-actions@overheid-plugins \
       jdtls-lsp@anthropic-plugin-directory \
       quarkus-agent@anthropic-plugin-directory \
       pr-review-toolkit@anthropic-plugin-directory \
       security-guidance@anthropic-plugin-directory; do
     claude plugin install "$plugin" --scope local
   done
   ```

   `--scope local` schrijft naar je eigen `.claude/settings.local.json`. Met `--scope project`
   herschrijft Claude Code de ingecheckte `.claude/settings.json`.

   Java 25 komt uit [SDKMAN](https://sdkman.io/): `sdk install java 25.0.5.0+8-tem`, of een
   nieuwere 25-versie uit `sdk list java`. Herstart Claude Code daarna.

| Plugin | Wat het doet | Nodig |
|--------|--------------|-------|
| `standaarden` | NL GOV API Design Rules, CloudEvents-profiel (feed en webhook), Logboek Dataverwerkingen | |
| `developer-overheid` | API's bouwen en controleren volgens de overheidsrichtlijnen | |
| `nerds` | Richtlijnen voor digitale overheidssystemen, onder meer beveiliging en privacy | |
| `zad-actions` | Een mislukte ZAD-preview-deploy onderzoeken | |
| `jdtls-lsp` | Java-taalserver: Claude ziet compileerfouten direct na een edit en kan definities en referenties opzoeken | `jdtls` |
| `quarkus-agent` | Zoeken in de Quarkus-documentatie en werken met dev mode | JBang; Podman voor het zoeken in de docs |
| `pr-review-toolkit` | Reviewagents, onder meer voor testgaten en stil afgevangen fouten, en `/review-pr` | |
| `security-guidance` | Beveiligingswaarschuwingen bij elke edit, een review van de diff aan het eind van elke beurt en een review bij commit en push | `python3`; kost per beurt extra tokens en tijd |

Ontbreekt een hulpprogramma, dan doet alleen die plugin het niet; de rest werkt gewoon.

Wat je daarna krijgt:

- **Hooks** die een wijziging aan gegenereerde code of een toegepaste migratie tegenhouden,
  waarschuwen bij `now()` zonder `Clock`, en `./mvnw verify` draaien vóór een `git push`. Ze
  gebruiken `jq`; Java komt uit SDKMAN (`source "$HOME/.sdkman/bin/sdkman-init.sh"`). Ontbreekt
  een van beide, dan slaat de hook over met een melding en vangt CI het af.
- **`/nmc-review <PR-nummer>`**: review met de projectchecklist, en de fixes als PR bovenop die
  PR in plaats van losse comments.
- **De agent `nmc-reviewer`** voor een onafhankelijke review van je eigen branch.

Persoonlijke instellingen horen in `.claude/settings.local.json`; die wordt niet ingecheckt.

**Let op: een `CLAUDE.md` zet `AGENTS.md` stil uit.** Claude Code leest `AGENTS.md` alleen als
er geen `CLAUDE.md`, `.claude/CLAUDE.md` of `CLAUDE.local.md` staat in de projectmap of in een
map erboven, bijvoorbeeld een `~/Projects/CLAUDE.md` voor al je repo's. Staat er wel een, dan
gelden de werkregels van dit project niet, zonder melding. Wil je zo'n bestand houden, zet dan
in `/config` de instelling *Project instructions* op `claude-md-and-agents-md`, of zet
`@AGENTS.md` in je eigen `CLAUDE.md`.

## Gedragscode

Dit project hanteert een [gedragscode](CODE_OF_CONDUCT.md). Door bij te dragen ga je akkoord met de daarin beschreven normen.

## Licentie

Bijdragen worden beschikbaar gesteld onder de [EUPL-1.2](LICENSE) licentie.

## Contact

Vragen? Neem contact op via [moza@minbzk.nl](mailto:moza@minbzk.nl) of open een issue.
