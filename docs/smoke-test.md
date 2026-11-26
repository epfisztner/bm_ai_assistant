# Manual smoke run

The real bankmonitor.hu pages and real language models are not part of the automated tests. This script checks the
whole stack by hand: live (or replayed) ingestion, retrieval, Citations per Bank Product, the Product Category
filter, a Refusal, a follow-up Question, feedback and the audit record.

Expected behavior is given for the **offline** provider (deterministic, verified on 2026-11-26 against the live
pages) and for a **real model** (LM Studio or Anthropic). Live page content changes over time, so exact sentences,
ids and figures may differ; the Citation behavior should not.

## 0. Start and check the Knowledge Base

```bash
docker compose up --build -d          # add CHAT_PROVIDER=... etc. for a real model, see README
curl -s localhost:8080/actuator/health/readiness             # {"status":"UP"} once seeding has finished
curl -s localhost:8080/api/v1/sources                        # 9 Sources, each with a currentVersion
```

Expected: 9 Sources (5 bankszámla, 2 lakáshitel, 2 személyi kölcsön), each `ACTIVE` with `lastFetchOutcome`
`NEW_VERSION` on the first start. Running `POST /api/v1/ingestion/runs` again reports `UNCHANGED` for every Source
whose page did not change. For a repeatable run, start once with the default `SNAPSHOT_MODE=record`, then use
`SNAPSHOT_MODE=replay`.

The steps below use `curl` from a bash shell; in Swagger UI (<http://localhost:8080/swagger-ui.html>) use the same
bodies and set `X-Expert-Id: anna`. Each step starts its own Conversation unless it says otherwise; note the
`id` values returned.

```bash
API=localhost:8080/api/v1
H=(-H 'Content-Type: application/json' -H 'X-Expert-Id: anna')
start() { local body='{}'; [ -n "$1" ] && body=$1; curl -s "${H[@]}" -X POST $API/conversations -d "$body"; echo; }
# the body goes through stdin so accented text stays UTF-8 (Git Bash on Windows mangles it in arguments)
ask()   { printf '{"question": "%s"}' "$2" | curl -s "${H[@]}" -X POST $API/conversations/$1/turns --data-binary @-; echo; }
```

## 1. CIB ECO vs CIB ECO Plusz: free period per Bank Product

The CIB ECO page also advertises CIB ECO Plusz, so the same page carries both "2 évig" and "5 évig".

```bash
start                                         # -> Conversation A
ask A 'Hány évig ingyenes a CIB ECO bankszámla?'
start                                         # -> Conversation B
ask B 'Hány évig ingyenes a CIB ECO Plusz bankszámla?'
```

| | Offline | Real model |
|---|---|---|
| CIB ECO | `ANSWERED`: "...a 2 évig ingyenes CIB ECO bankszámlánál..." | `ANSWERED`, 2 years |
| CIB ECO Plusz | `ANSWERED`: "5 évig ingyenes a CIB Mastercard Gold bankkártyád is..." | `ANSWERED`, 5 years |

Check: every Citation of the CIB ECO Turn has `bankProduct` `CIB ECO`, every Citation of the Plusz Turn has
`CIB ECO Plusz` (different `sourceVersionId`s). A "2 év" statement must never be cited to the Plusz Source or the
other way round. The offline stand-in only quotes, so it picks whichever sentence matches best (here the Plusz
page's Gold card sentence); a real model states the account's 5-year free period from the same Source.

## 2. Follow-up Question

Continue Conversation B:

```bash
ask B 'Mekkora havi jóváírás kell hozzá?'
curl -s -H 'X-Expert-Id: anna' $API/conversations/B
curl -s -H 'X-Expert-Id: anna' "$API/conversations?limit=10"
```

Expected: `standaloneQuestion` carries the Bank Product from Turn 1. Offline, the rewriter simply prepends the
previous Standalone Question ("Hány évig ingyenes a CIB ECO Plusz bankszámla? Mekkora havi jóváírás kell hozzá?")
and the Answer stays on CIB ECO Plusz Sources; a real model rewrites it into one question (e.g. "Mekkora havi
jóváírás kell a CIB ECO Plusz bankszámlához?") and answers 450 000 Ft, cited to CIB ECO Plusz. `GET /conversations/B`
returns both Turns in order with their Citations, and `GET /conversations` lists Anna's Conversations most recently
active first (Conversation B at the top with `turnCount: 2` and the follow-up as `lastQuestion`, followed by Conversation A).

## 3. Erste income threshold

```bash
start                                         # -> Conversation C
ask C 'Mekkora havi jövedelem szükséges az Erste ingyenes bankszámlához?'
```

Expected: `ANSWERED` with 225 000 Ft for Erste Privilégium 2.0 Partner. Offline it quotes the comparison page
("Az Erste Privilégium 2.0 Partner számlához legalább 225 000 Ft, a CIB ECO Plusz bankszámlához 450 000 Ft...",
cited to "Ingyenes bankszámlák összehasonlítása"); a real model may cite the Erste page ("min. 225 ezer Ft
egyösszegű havi jövedelem szükséges") instead or as well. Every figure in the Answer appears in a cited Passage.

## 4. Product Category filter (lakáshitel)

```bash
start '{"productCategory": "LAKASHITEL"}'     # -> Conversation D
ask D 'Maximum hány éves futamidejű lehet a Fogyasztóbarát Lakáshitel?'
curl -s "${H[@]}" -X POST $API/conversations/D/turns --data-binary @- \
  <<<'{"question": "Maximum hány éves futamidejű lehet a Fogyasztóbarát Lakáshitel?", "productCategory": "BANKSZAMLA"}'
```

Expected: the first Turn has `productCategory` `LAKASHITEL`, answers "Maximum 30 éves futamidő" and every Citation
is a lakáshitel Source. The second Turn overrides the filter to `BANKSZAMLA`: only bankszámla Passages are searched,
so a real model declines (`MODEL_DECLINED`) or the Answer cites only bankszámla Sources and is about something
else; offline quotes an unrelated bankszámla sentence (the extractive stand-in cannot judge relevance). Either way,
no lakáshitel Source is cited.

## 5. Question outside the Knowledge Base

```bash
start                                         # -> Conversation E
ask E 'Hogyan kell kenyeret sütni?'
```

Expected (both providers): `REFUSED`, `refusalReason` `NO_EVIDENCE`, no Citations; the model is not called.
With a real model, a Question that shares a few words with the Knowledge Base but is not answered by it (e.g.
"Mennyi a bitcoin árfolyama ma?") is `REFUSED` with `MODEL_DECLINED` and lists `relevantSources`. Offline, the same
Question is answered with an unrelated quoted sentence: the stand-in has no judgement, which is exactly why a real
model is needed for the decline path.

## 6. Feedback and audit

Use the id of the follow-up Turn from step 2 (`T`):

```bash
curl -si "${H[@]}" -X POST $API/turns/T/feedback -d '{"rating": "OUTDATED", "comment": "smoke test"}'
curl -s "$API/feedback?rating=OUTDATED"                     # Knowledge Owner queue (no X-Expert-Id in PoC)
curl -s -H 'X-Expert-Id: anna' $API/turns/T/audit
curl -s -H 'X-Expert-Id: bela' $API/turns/T/audit      # another Expert
```

Expected:
- feedback: `201`, `citedSourceVersions` lists the CIB ECO Plusz Source Version.
- feedback queue: `200`, lists the submitted `OUTDATED` feedback newest first across all Experts with `question`,
  `productCategory`, `turnStatus` and `citedSourceVersions` (Knowledge Owner endpoints `/sources`, `/ingestion/runs`
  and `/feedback` have no `X-Expert-Id` filter in the PoC and are role-gated in the production NestJS BFF).
- audit: `chatProvider`/`chatModel` (`offline`/`extractive-v1` or the configured model), `promptVersion`
  `answer-v3`, `rawModelOutput`, `verdict.passed` `true`, `embeddingModel` (null without embeddings), and
  `retrievedPassages` with every fused retrieval candidate (`lexicalRank`, `vectorRank`, `score`, `inPrompt`,
  `marker` for those presented). For this follow-up, `rewrite` shows the question-rewrite step: `promptVersion`
  `rewrite-v1`, the model's `rawOutput`, its `chatProvider`/`chatModel` (offline: `offline`/`join-v1`), and
  `fellBackToJoin` (`true` when a blank reply made the service join the previous Standalone Question with the
  Question). It is `null` for a first Turn.
- another Expert: `404` problem detail.

## 7. The same scenarios in the API test console

Open <http://localhost:8080/>. The console is a single static page served by the service; it uses only the
`/api/v1` endpoints above and shows every call's raw HTTP exchange in a collapsible block.

1. The Sources panel lists the 9 Sources (`Aktív`, current version, last fetch outcome). "Betöltés indítása"
   starts an ingestion run: every unchanged Source shows `Változatlan`, and the list is refreshed.
2. With Szakértő azonosító `anna`, click "Új beszélgetés", click the "CIB ECO" sample chip (it only fills the
   Kérdés field), send it: the Turn is `Megválaszolva`, every Hivatkozás shows Banki termék `CIB ECO`, its
   Forrásverzió id and version number. Repeat in a new Conversation with "CIB ECO Plusz", then send the
   follow-up chip in the same Conversation: the card shows the rewritten Önálló kérdés.
3. Choose `lakáshitel` as the default Termékkategória, start a new Conversation and send the Fogyasztóbarát
   Lakáshitel sample: every Hivatkozás is a lakáshitel Source. Send it again with the per-question override set to
   `bankszámla`: no lakáshitel Source is cited.
4. "Hogyan kell kenyeret sütni?" in a new Conversation is `Elutasítva` with the no-evidence reason and no Citations.
5. On a Turn, click "Elavult" with a comment: the stored feedback appears under the buttons. "Auditrekord
   megnyitása" shows the Standalone Question, the rewrite, the retrieved Passages with their ranks, the prompt
   version, the raw model output and the verdict.
6. Errors: send an empty Question (400 with the `question` field error), clear the Szakértő azonosító and start a
   Conversation (401, missing `X-Expert-Id`), change it to `bela` and ask in `anna`'s Conversation (404). Each
   shows a red notice next to the action. Stop the service and click "Frissítés": a Hungarian "service unreachable"
   notice appears.

## 8. Clean up

```bash
docker compose down        # keep the Knowledge Base and snapshots
docker compose down -v     # or drop them
```
