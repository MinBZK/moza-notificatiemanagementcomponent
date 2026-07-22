# NotificatieManagementComponent (NMC)

## What this is
This repo is the **NMC only**. It's the centralized hub that takes a notification
request from a Dienstverlener-side caller, composes the message (via a separate
Templating Service), sends it through NotifyNL, processes the delivery result,
and — if digital delivery fails — orchestrates **contactherstel** (falling back
to a physical letter via Printstraat/Postadres).

Everything else (OMC, Profielservice, Templating Service, NotifyNL,
KvK/BRP/NHR, DCProfiel) is a separate system/repo. NMC's job is to standardize
logic that individual Dienstverleners would otherwise have to build themselves.

## Domain glossary
- **Dienstverlener** — the government service provider sending the notification
- **Procesapplicatie** — the Dienstverlener's backend that decides a notification is needed
- **OMC (Output Management Component)** — `../moza-omc` (.NET). A per-Dienstverlener
  component, similar in purpose to NMC but scoped to one Dienstverlener. Many
  Dienstverleners already run one; NMC standardizes what would otherwise be
  duplicated per-OMC logic. OMC is the typical caller of NMC, but a Dienst/voorziening
  without an OMC can also call NMC directly.
- **DCProfiel** — a Dienstverlener's own decentralized contact-preference store, used by OMC
- **Profielservice** — `../moza-profiel-service` (Quarkus). Central profile/preference
  service: resolves BSN/KVK/RSIN → contact preferences/data, manages contactherstel
  options, and can have an email address invalidated
- **Templating Service** — not built yet. Provides message templates by berichttype
- **Notify (NotifyNL)** — Dutch GOV.UK Notify fork. Actual send channel for email
  and (via Printstraat) physical post
- **Printstraat / Postadres** — physical mail printing & postal delivery, reached via Notify
- **KvK/BRP/NHR** — external registries (Kamer van Koophandel / Basisregistratie
  Personen / Nieuw Handelsregister), used as a fallback address source for contactherstel
- **Contactherstel** — falling back to an alternative contact channel (typically a
  physical letter) when digital delivery fails
- **Herverzending** — retrying the *same* channel after a failure (e.g. on a 4.x.x error)
- **wMEBV** — believed to be *Wet modernisering elektronisch bestuurlijk verkeer*,
  the law driving proof-of-delivery ("bewijslast") and contactherstel obligations.
  Not yet confirmed.
- **BSN / KVK / RSIN** — citizen / company / legal-entity identifiers

## The two independent axes that drive NMC's behavior
These are orthogonal — don't conflate them.

**Axis 1 — Is there an OMC in front of NMC?**
- Yes: OMC is the caller; NMC reports status back to OMC, which forwards to Procesapplicatie
- No: a Dienst/voorziening without its own OMC calls NMC directly

**Axis 2 — Profiel type: centraal vs decentraal** (this is the one that actually
changes NMC's logic)
- **Centraal profiel — NMC is "in the lead"**: the caller (OMC or a direct
  Dienst/voorziening) supplies only an identifier (BSN/KVK/RSIN + Dienstverlener +
  Dienst + berichttype + bericht-specifieke info). NMC then:
  - calls Profielservice to resolve the contact preference/data
  - on delivery failure: invalidates the email at Profielservice, queries
    Profielservice for contactherstel options, and falls back to KvK/BRP/NHR for
    an address if Profielservice has nothing
  - owns the entire contactherstel flow end-to-end ("volledige ontlasting")
- **Decentraal profiel — NMC is a "doorgeefluik" (pass-through)**: OMC has already
  resolved contact preference/data via its own DCProfiel and sends NMC the full
  picture (incl. email address, verzendtype, berichttype, etc.). NMC then:
  - composes & sends as instructed
  - on delivery failure: reports the outcome back to OMC, which updates DCProfiel itself
  - contactherstel is decided/initiated by OMC; NMC just executes what it's told
    ("gedeeltelijke ontzorging")

An OMC being present does **not** imply decentraal profiel — an OMC can still pass
through only a bare identifier and let NMC lead.

## Flow phases (NMC's perspective)
1. **Resolve receipt preference & contact data** — NMC resolves via Profielservice
   (centraal) or receives it pre-resolved from the caller (decentraal)
2. **Compose & send** — fetch template from Templating Service, fill it, submit to Notify
3. **Capture & register response** — handle Notify's delivery outcome
   (success/failure), invalidate email or inform OMC, decide on herverzending
4. **Resolve contactherstel data** — centraal: Profielservice → fallback
   KvK/BRP/NHR; decentraal: provided by the caller
5. **Execute & follow up contactherstel** — digital (same template/send/response
   flow) or physical (Notify → Printstraat → Postadres), report status back up

## Integration assumptions
- **NotifyNL send is synchronous, delivery status is async.** The initial
  send is a synchronous HTTP POST (JWT built from the API key) that only
  confirms "accepted for sending" (200/201) — not actual delivery. The real
  delivery outcome arrives via a separate async webhook. NotifyNL callbacks
  are configured once, per service/API key, in NotifyNL's dashboard ("API
  integration" → "Callbacks"): a URL plus a bearer token that NotifyNL sends
  back in the Authorization header — not a field on the send-email request
  itself (see `src/main/resources/openapi/notifynl_api.yaml:74-85`).
  `/api/nmc/v1/notify-callback` (`NotifyCallbackController`) is that
  registered URL, and the bearer token is what its no-auth TODO should
  validate against.
- **Herverzending vs contactherstel trigger logic is out of scope for this
  skeleton.** Herverzending retries the same channel. Deciding *when* to escalate
  to contactherstel is a later story — leave it as an extension point, don't build it now.
- **Templating Service** doesn't exist yet. A simple mock returning one fixed
  template is sufficient for now — low priority.
- **Observability koppelvlak** (exposing processing/status info to Dienstafnemers,
  likely for wMEBV bewijslast) is **not a priority for this skeleton**, but the
  data model should naturally support an audit trail of statuses/attempts per
  notification so this can be added later without a redesign.
- **`NotificatieStatus` vocabulary** (`created, sending, pending, sent,
  delivered, accepted, received, cancelled, permanent-failure,
  temporary-failure, technical-failure`) is grounded directly in GOV.UK
  Notify's own published delivery-receipt statuses. We earlier also looked at
  `moza-portaal/dependencies/omc/swagger.json`'s `DeliveryReceipt`/
  `DeliveryStatuses` schema as corroborating evidence, but per Joeri that
  swagger likely belongs to a *different* system than the per-Dienstverlener
  OMC (`../moza-omc`) — possibly an "Output Management Systeem"
  (e.g. a Printstraat/output system). Treat that swagger as **unconfirmed**;
  it doesn't block the current `NotificatieStatus` design (which stands on its
  own via Notify's docs), but should be clarified before being used as the
  contract for future OMC-status-reporting / Printstraat integrations.

## Reference repos (siblings, same level as this repo)
- `../moza-omc` — OMC, .NET (Moza.Omc.Api)
- `../moza-profiel-service` — Profielservice, Quarkus (`src/main/openapi/api_basisprofiel.yaml`)
- `../moza-verificatie-service` — Quarkus; reference for the NotifyNL integration pattern
- `../moza-portaal` — Next.js portal; has `dependencies/omc/swagger.json`. Possibly
  an "Output Management Systeem"/Printstraat API contract rather than `../moza-omc`
  — relation unconfirmed, see the `NotificatieStatus` caveat above

## Technical conventions for this project
- Stack: Java 25 + Quarkus 3.35.1 (RESTEasy Reactive, Hibernate ORM/Panache),
  groupId `nl.rijksoverheid.moz`
- **Use constructor-based dependency injection, not field injection** — a
  deliberate deviation from other projects in this org that use field injection
- Repositories use `PanacheRepositoryBase<T, Id>` (not the active-record
  `PanacheEntity`/`PanacheEntityBase` static-finder style used in sibling repos),
  specifically so they're constructor-injectable. Use `PanacheRepositoryBase`
  rather than the simpler `PanacheRepository` whenever the entity's `@Id` isn't
  a `Long` (e.g. `Notificatie`'s id is a `UUID`) — `PanacheRepository<T>` fixes
  the id type to `Long`, which silently breaks id-based methods like
  `deleteById` for any other id type.
- Error handling: client/service code throws plain exceptions; only
  Controllers build responses, via RFC 9457 `HttpProblem` (Quarkiverse
  `io.quarkiverse.httpproblem`, wrapped by the `Problems` helper) — no
  `GlobalExceptionMapper`/`ErrorResponse` abstraction (kept minimal for this
  skeleton)

## Status
- Inbound API contract (`/api/nmc/v1`: **centraal** create notification
  `POST /centraal/notificaties` — resolves contact data via Profielservice;
  **decentraal** create notification `POST /decentraal/notificaties` — caller
  supplies the email address directly, no Profielservice lookup; Notify
  delivery-receipt webhook) and the domain model/repositories/service layer
  behind it are implemented, with `@QuarkusTest` + RestAssured controller
  tests. Both create-flows share `NotificatieService.verstuurNaarEmail(...)`
  (persist → NotifyNL → status); the centraal flow prepends the Profielservice
  lookup. `get status` and `initiate contactherstel` are planned but not yet
  implemented — no such endpoints exist yet
- Outbound integrations to Profielservice and NotifyNL are implemented as
  real `@RestClient`-backed adapters (mocked only in tests)
- See `README.md` (Dutch) for a functional overview
- Not yet implemented (see "Integration assumptions" above): the Templating
  Service integration, OMC integration, the orchestration logic that ties the
  flow phases together, and herverzending vs. contactherstel trigger logic
