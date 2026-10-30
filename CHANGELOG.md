# CHANGELOG — Local-life-service-platform

Application repository (Java backend + Vue frontend), **no upstream**. This record keeps the repairs,
the drift guards and the process changes — the things that a rebuild, a dependency bump, or a
re-run of a frozen artifact can invalidate.

> **Note.** Paths under `docs/` named below are engineering notes kept outside this
> repository; each entry records them as they stood at that commit.

Entries are `D-###`, monotonic, never reused; a gap is a failure, not cleanup. An entry states what
was true **as of its commit**, not current state, and aggregate counts are printed by a command,
never copied into this file. `kind` ∈ `fix | perf | taste | guard | revert | chore`.
`grep '^### D-.*· fix' CHANGELOG.md` lists the entries that changed a code path; `chore` entries
changed records only.

> **No coverage gate.** This repository has no `check:log`-style record-coverage checker, so nothing
> enforces that a code-path commit appears here. The record is maintained by hand; treat a missing
> entry as a gap to close, not as permission. Performance and test figures are **not** restated here
> — they stay traceable to the artifacts under `backend/test-results/verify/`.

---

### D-001 · 2026-10-10 · chore
Symptom  The root-file layer was ungoverned: `AGENTS.md` was Chinese-only, there was no `CHANGELOG`
         or `MAINTENANCE`, and `.gitignore` lacked the machine-wide runtime ignore list.
Change   Adopt the machine-wide `STANDARD.md` conventions; translate `AGENTS.md` to English and add
         the Chinese companion `AGENTS.zh-CN.md`; add this `CHANGELOG.md` and the
         `MAINTENANCE.md` / `MAINTENANCE.zh-CN.md` pair; add the runtime ignore list to `.gitignore`.
Evidence Docs, `CHANGELOG` and ignore list only — no code path touched.
Cost     None to runtime behaviour. `docs/**` stays Chinese by design (project-specific exception).

### D-002 · 2026-10-10 · guard
Symptom  The bilingual guard only compared README `##` section counts, so the English
         `README.md` could say a response was `202` while the Chinese README and the code
         both say `200` and the guard stayed green.
Change   Extend `scripts/check_bilingual.py`: besides the `##` count it now compares the
         HTTP status codes each side names, and it runs over the README / AGENTS / MAINTENANCE
         pairs (the README still has to open with the language switcher).
Evidence The guard passes on the current pairs and fails on a synthetic `202`/`200` pair.
Cost     A code counts only in an HTTP context (or in backticks), so `500 ms` is not mistaken
         for one — checked clean across this machine's repos.

### D-003 · 2026-10-13 · guard
Symptom  `AGENTS.md` said this repository "is not listed in its §0 table" of the machine-wide
         `STANDARD.md`. That stopped being true: the standard now classifies full-stack
         applications as dimension B, so the pointer named a document that no longer governs
         this repository — and nothing checked that, or the root layer it stands on.
Change   Re-aim the pointer at `CONVENTIONS.md` §0–§2 in both `AGENTS.md` and
         `AGENTS.zh-CN.md` (one blockquote, rules left there); add `scripts/check_layout.py`
         asserting the root layer, the absence of a LICENSE and of a root manifest, the
         one-line dimension pointer, and that nothing the ignore list covers is tracked; run
         it as a second step in `docs-ci.yml` beside the bilingual guard.
Evidence `python3 scripts/check_layout.py` → 5/5 checks pass, rc=0; `check_bilingual.py` →
         3 pairs in sync, rc=0. Provoked red by touching `LICENSE`, which failed exactly the
         LICENSE check with its fix text, then removed.
Cost     None to runtime behaviour. The guard's first run already found nothing wrong here
         (this repository tracks no IDE files), and one first-draft bug was fixed in the guard
         itself: an empty pathspec list made `git ls-files` dump the whole tree, so the filter
         moved into Python.

### D-004 · 2026-10-15 · chore
Symptom  Every `README` opened straight into its first section: there was no short
         "what / why / how / where" lead-in, so a first-time reader had to scan the whole
         document to learn what the repository is or how to run it.
Change   Insert the standard four-part opening (`What it is` / `What it solves` / `How to run`
         / `Where to look`) after the badges, in `README.md` and `README.zh-CN.md`, each side
         in its own language. Nothing else in either README is touched.
Evidence Docs only — `git status` lists exactly `README.md` and `README.zh-CN.md`;
         `python3 scripts/check_bilingual.py` → 3 pairs in sync, rc=0;
         `python3 scripts/check_layout.py` → 5/5 checks pass, rc=0.
Cost     None. The four items restate nothing below them, and the `Where to look` pointer
         resolves (`AGENTS.md`, `MAINTENANCE.md`).

### D-005 · 2026-10-16 · chore
Symptom  The pointer in AGENTS.md is followed only by an agent that chooses to; nothing made the
         standard reach the context, and the section list had drifted — §5 (the conflict rule) was
         missing.
Change   End the pointer block with two bare `@` imports and add `opencode.json` naming the same two
         files in its `instructions` array; add `§5 conflict rule`.
Evidence 2026-10-16: CodeBuddy CLI with every tool disabled printed the tokens the imports carried;
         opencode `run` printed the token its `instructions` named, with no tool call.
Cost     Both hooks name `~/.agents`, absent in another clone, so they are skipped there — the
         pointer stays the portable part.

### D-006 · 2026-10-16 · chore
Symptom  The blacklist entry in `AGENTS.md` and `AGENTS.zh-CN.md` pointed at
         `docs/04-testing/reports/test-report.md`, a path that no longer exists — the report was
         moved to `docs/_archive/verification-summary.md` (the move is recorded in
         `docs/BACKEND_FREEZE.md`). A dangling pointer in the blacklist is the worst place for one:
         it tells an agent that a protected artifact has no settled conclusions to preserve.
Change   Point both files at `docs/_archive/verification-summary.md`; the clause "new sections may
         be appended, old numbers may not be rewritten" is unchanged, and the English and Chinese
         sides stay consistent.
Evidence `docs/_archive/verification-summary.md` exists and `docs/04-testing/reports/` does not;
         `python3 scripts/check_bilingual.py` → 3 pairs in sync, rc=0;
         `python3 scripts/check_layout.py` → 5/5 checks pass, rc=0.
Cost     Docs only — no code path touched, and `docs/**` stays Chinese by design (this project's
         own exception).

### D-007 · 2026-10-18 · guard
Symptom  The seckill concurrency test treated "Pending is zero" as "consumption finished". Pending
         is also zero *before* the consumer has read anything, so the wait loop could exit on its
         first poll; the consumer then pulled the backlog into its PEL and the re-read asserted
         against 30 in-flight messages. The test failed intermittently with no product defect —
         a test-side race, not a lost or duplicated order.
Change   Require both conditions before calling it done: every entry delivered
         (`lastDeliveredId == lastGeneratedId`) and Pending back to zero, polled every 200ms with
         a 15s budget. A separate `drained` assertion prints both ids when the budget runs out,
         so a timeout names which half failed instead of only reporting a pending count.
Evidence 2026-10-18, local MySQL + Redis: `mvn -Dtest=VoucherOrderTest test` four consecutive runs
         → 1/1 green each; the run reports DB final stock 0 (all 50 accepted requests consumed),
         where the racing version could report 50 with orders still in flight.
Cost     Test-only, no production path touched. The worst case per run grows from 10s to 15s.
         The consumer's `NOGROUP` error lines are pre-existing noise: `setUp` deletes the stream,
         the consumer re-creates the group, and those lines are not failures.

### D-008 · 2026-10-19 · chore

Symptom  AGENTS.md cited the shared standard by bare STANDARD.md, a name that only resolves via
         the pointer block above it.
Change   The reference now carries the full ~/.agents/standards/STANDARD.md path.
Evidence 2026-10-19: grep -n STANDARD.md AGENTS.md shows only full paths.
Cost     None; wording only.
Commit   c81b471

### D-009 · 2026-10-19 · fix

Symptom  The id-generation row of the mechanism table said `（时间戳<<32 | 每日 INCR 序列）`; the bare
         pipe split the row into four cells while the header has three, so GitHub rendered an
         empty fourth cell.
Change   The pipe is escaped as `\|`; the rendered text is unchanged and no other character moved.
Evidence 2026-10-19: every table block in the file has a uniform pipe count; the diff is one
         character. Freeze application and owner approval: docs/BACKEND_FREEZE.md §7.7 UF-006.
Cost     Another frozen-docs exception on record (UF-006); no code, number or wording touched.

Commit   91961575

### D-010 · 2026-10-23 · fix

Symptom  Every knowledge-base link in `docs/02-interview/bagu-map.md` (133 `file://` links over 97
         distinct notes) and the one in `docs/02-interview/database-design.md` pointed into
         `~/Documents/01-Learning/...`. The vault directory was renamed
         to `01-Knowledge-Base` and its Java backend was re-moduled at the same time (16 modules to
         22, notes renumbered), so every one of them resolved to nothing.

Change   Each link now names its current path: the module prefix follows the new layout and the file
         prefix follows the note's new number. Four targets had no same-named successor and were
         mapped by section structure: `Spring IOC与AOP` → `03-Spring AOP` (the referencing rows are
         about proxies), `Spring Security` → `10-认证与接口可靠性/04-Spring Security`,
         `Redis Stream` → `03-Redis Stream`, and `工程规范与代码质量` → `07-后端开发基础`
         (identical five-section outline). The single link in `database-design.md` follows the same
         module rename (`05-数据库/01-MySQL专题` → `06-MySQL与持久层`).

Evidence 2026-10-23: a script resolves all 134 `file://` targets across both files against the
         filesystem — 0 dead.

Cost     Link targets and their labels only; no row, wording or tier changed, and no note was edited.
         Both files are still untracked, so this entry also covers their first commit.

Commit   c5cff088

### D-011 · 2026-10-23 · chore

Symptom  The set could be read, recited and searched but never **checked**: every document explains
         something, none asked whether the reader could say it back. The nearest thing was
         `must-memorize` §七's tick list, which is a reminder rather than a judgement — six checkboxes
         cannot tell you which stage you actually failed.

Change   Add `docs/02-interview/self-check.md`: nine stages (业务定位 → 架构取舍 → 数据模型 → 演进史 →
         秒杀主链 → 订单锁缓存 → 边界抗压 → 表达临场 → 综合), 57 questions, a 0–3 rubric whose rule is
         that anything below 2 blocks the next stage, and a 「卡住就回」 column naming the file and section
         that holds the answer. Wire it into `docs/README.md` (tree line plus the 验收 paragraph) and state
         the division of labour with `demo-and-mock`'s mock-interview appendix.

Evidence Stage counts 5/8/6/5/9/9/6/6/3 = 57, printed by a script over the file. Every 「卡住就回」 target was
         read from the actual headings rather than from memory, and every question was checked to be
         answerable from the document it points at. Guards: bilingual 3 pairs, layout green.

Cost     The page deliberately carries no answers, so it is useless without the other twelve files — a reader
         who opens only this one finds 57 questions and nothing to check against. The rubric is self-scored,
         which is the weakest form of measurement: it catches 「说不出」, not 「说得很顺但内容是错的」.

Commit   74f066da

### D-012 · 2026-10-23 · chore

Symptom  The one-hour page covered everything said **to** the interviewer and nothing said **back**. The last
         five minutes — 「你有什么想问我的」 — had no preparation, and the two usual failures there are asking
         what the careers page already answers, or asking nothing and ending the round on a shrug.

Change   Add §七 反问清单: five questions split by who is across the table (一线开发 / 技术负责人 ×2 / 主管 / HR),
         each with 「你想听出什么」 rather than a script; a 别问的四类 list; one 加分动作. Renumber the
         checklist to §八 and add one line to it. The first question deliberately routes back to this
         project's strongest ground — 热点写 and 异步链路 — so whatever the interviewer answers can be
         followed up with something actually built.

Evidence No document referenced `must-memorize.md` §七 or §八 before this (grep over `docs/`), so the renumber is
         safe; the one live reference is `self-check.md:147` → §二 / §三 / §五, none of which moved. Guards:
         bilingual 3 pairs, layout green, 134 KB links 0 dead.

Cost     The list is judgement, not evidence — it makes no claim about any employer, so a wrong guess costs a
         question rather than credibility. One asymmetry is deliberate: the first question invites a number
         from the interviewer while this project's own rule is to refuse unverified numbers, so the row tells
         the reader not to push for one.

Commit   2afa4087

### D-013 · 2026-10-23 · chore

Symptom  Practice had no memory. `demo-and-mock` §附 says how to run a mock interview and `self-check` scores
         what you can say alone, but nothing recorded **what actually happened** — so the same question broke
         the same way three sessions in a row and no one could see it.

Change   Add `docs/02-interview/interview-simulation.md`: 记录原则, a five-step flow, L1/L2/L3 mapped onto
         `pressure-test`'s three attack types, a copyable record template (score row, question table, 卡壳点,
         答错, 复盘四问, one actionable fix), a filled 示范 explicitly labelled as not a real session, a 1–5
         rubric, and an empty 记录区 plus a five-session summary table. Wire it into `docs/README.md` and
         point `self-check`'s division-of-labour row at it.

Evidence The 示范 uses only facts already in the docs: Q08 (失败回滚), Q11–Q12 (Pending / XCLAIM), and
         「消费者名每次随机」 from `must-memorize` 亮点 2. Its 说满 example is the exact red line in
         `boundaries` §3 (「Stream 绝对不丢」), so the record format and the boundary list agree. Guards:
         bilingual 3 pairs, layout green, 134 KB links 0 dead.

Cost     A template that is filled once and then abandoned is worse than none — it looks like process. The
         file says 「没有记录就是没练过」 and leaves §五 empty rather than seeding it with the 示范. The 1–5
         rubric is the second scoring scale in this set (self-check uses 0–3); both files say not to mix them.

Commit   9f33f96d

### D-014 · 2026-10-23 · chore

Symptom  Two things had drifted by the time the set was closed. `must-memorize` still opened with 「从全套 docs 约
         4300 行里提炼」 while the set is now 5,935 lines — the same failure mode the repo bans for test numbers,
         a figure copied into prose instead of printed. And every round ended with one entry reading 待提交 with
         no written rule for when it gets filled.

Change   Drop the line count from `must-memorize` (it drifts by definition and told the reader nothing useful),
         backfill D-013, and state the convention here: the newest entry keeps 待提交 until the next commit
         touches this file; exactly one pending entry is the steady state.

Evidence `wc -l` over `docs/**/*.md` → 5,935 lines across 20 files; every `.md` under `docs/` appears in the README
         tree and every file under `02-interview/` appears in its tree block; `self-check` prints stage counts
         5/8/6/5/9/9/6/6/3 = 57 and its 总表 carries the same nine numbers; `qa-bank` holds 33 questions; the 134
         `file://` targets resolve with 0 dead. Guards: bilingual 3 pairs, layout green.

Cost     Dropping the count removes the only sense of scale the page offered — but that figure was already wrong by
         roughly a third, so it was never doing the job. The 待提交 rule costs one bookkeeping commit per round,
         which is the price of keeping the field honest without a script.

Commit   80cd3166

### D-015 · 2026-10-23 · chore
Symptom  `docs/BACKEND_FREEZE.md` was deleted by the owner — the freeze model is retired — but the
         root-file layer still depended on it. `AGENTS.md` opened with "read BACKEND_FREEZE.md first",
         `§1` said the authoritative blacklist lived there and must not be copied here, `§1` also
         carried a whitelist that only made sense while the rest of the backend was open, and both
         READMEs cited the file as the sole authority for every performance and test number. Twenty-
         one references, none of them guarded: `docs-ci` checks the bilingual pair, not links, so a
         green build said nothing about them. The other deleted file,
         `docs/_archive/verification-summary.md`, was still named in the AGENTS blacklist and in a
         README table row in each language.

Change   Put the rule in `AGENTS.md` instead of a separate document, in both languages: nothing under
         `backend/` is modified without the owner's explicit approval, there is no whitelist any more,
         and the per-file list survives only as "the spots most easily stepped on". Replace the thaw
         template with a four-line application (file, why, which highlight and figure, rollback).
         Point `MAINTENANCE` and both READMEs at `AGENTS.md` and at the new `backend/README.md` — a
         package/HTTP/Redis/test map of the backend — and make every figure trace to
         `backend/test-results/verify/` rather than to a document.

Evidence `mvn -B test` in the backend container → 212 cases / 34 classes, 0 failures, 0 errors, 0
         skipped; the README's testing table already matched and is unchanged. `grep -rn
         BACKEND_FREEZE` over the six root files returns 0 hits. Read-only smoke on the running
         stack: `/health` 200, `/shops/1` 200, `/shops/999999` 200 with `40001`, an unauthenticated
         `/blogs/mine` 401 with `10001`.

Cost     `docs/**` still points at the deleted file in about twenty places; those are left for the
         documentation pass. A read-only audit of the backend ran in the same round and found real
         defects (notably the synchronous purchase entry accepting a seckill voucher outside its
         window). None of them is fixed here: no backend behaviour changed, and the findings go to the
         owner as a list first.

Commit   05785035

### D-016 · 2026-10-23 · fix
Symptom  The audit list from D-015, fixed. The worst was in the purchase path: `createVoucherOrder`
         (the synchronous `POST /voucher-orders/purchase-orders` entry) never looked at the voucher
         at all, so any logged-in user could buy a **seckill** voucher there — outside the time
         window, without the Lua pre-deduction and without the Stream — or buy an off-shelf one.
         Around it: a CAS stock-deduction failure compensated Redis even when the same `orderId` had
         already been persisted by a concurrent `XCLAIM` claimant, drifting Redis stock +1 forever;
         `queryOrderDetail` returned another user's order; `rollbackStock` added stock back for
         ordinary vouchers that had never deducted any; the Pending loop aborted the whole batch on
         one failing record, so every later Pending message in that batch starved; shop create/update
         never touched the GEO index, so a new shop stayed invisible to distance search until the next
         restart; `addSeckillVoucher` accepted `type=0`, non-positive stock and `end < begin`, and
         wrote the Redis stock inside the transaction; `FlywayConfig` ran `repair()` on every boot,
         silently dropping failed-migration history and letting the app start on a half-migrated
         schema; login never deleted the code it had just accepted, so one code could log in
         repeatedly for the rest of its 2-minute TTL; `updateUserInfo` wrote two tables with no
         transaction and reported failures as `99999`; and `CacheClient.unlock` deleted the lock key
         unconditionally, so a holder whose lock had expired could delete the next holder's lock.

Change   Four themes: (1) make the purchase entry validate the voucher it is about to sell;
         (2) make every Redis compensation conditional on this message not having landed; (3) stop
         one bad record or one stale row from blocking a whole path — the Pending batch, the GEO
         index, the two-table profile write; (4) stop configuration from hiding failure — Flyway
         repair, default secrets, unconditional lock release. Each item's mechanism, fix and code
         site is listed under `Detail`.

Detail   1. Purchase entry — `VoucherOrderServiceImpl.createVoucherOrder` now loads the voucher and
            rejects missing / seckill / off-shelf (`30000`, `30007`) before the one-per-user check;
            the seckill-stock deduction that never belonged there is gone.
         2. Redis drift — `createOrderByMessage` (CAS branch) and `handleStreamRecord` (unique-key
            branch) call `getById(orderId)` first: if this message already landed, skip the `+1` and
            the placeholder removal.
         3. Order read — `queryOrderDetail` answers `30008` for another user's order instead of
            returning its body.
         4. Stock rollback — `rollbackStock` returns early unless `tb_seckill_voucher` holds the row,
            so cancelling an ordinary voucher no longer invents stock.
         5. Pending starvation — `handlePendingList` catches per record, leaves the failed one
            un-ACKed and leaves the pass after a one-second backoff, so the rest of the batch is
            processed instead of being skipped forever.
         6. GEO — `createShop` writes the member; `updateShop` removes it from the old
            `shop:geo:{typeId}:{city}` when type or city changed and re-adds it under the new key.
            Both go through `syncShopGeo` / `removeShopGeo`, which log and never fail the write.
         7. Seckill creation — `addSeckillVoucher` requires `type=1`, stock > 0 and `begin < end`,
            and defers the Redis stock write to `afterCommit`.
         8. Flyway — `config/FlywayConfig` deleted: the unconditional `repair()` dropped
            failed-migration history, so the app could start on a half-migrated schema.
         9. Login code — `UserServiceImpl.login` deletes `login:code:{phone}` the moment it matches.
        10. Profile update — `UserInfoServiceImpl.updateUserInfo` is transactional, reads the row
            once (`exists` instead of a second `getById`), refreshes the login cache after commit,
            and answers `10001` / `10008`.
        11. Cache lock — `CacheClient` writes a per-acquisition UUID and releases through
            `UNLOCK_SCRIPT` (compare-and-delete), so an expired holder cannot delete a new one.
        12. Tests — `MerchantVoucherRedeemTest.createPendingOrder` routes seckill vouchers through
            `/voucher-orders/seckill-orders` and polls for the row; `VoucherContractTest` gains
            `purchaseEndpointRejectsSeckillVoucher`.
        13. Server config — `server.graceful-shutdown.*` bound to nothing, so shutdown was never
            graceful; replaced by `server.shutdown: graceful` plus
            `spring.lifecycle.timeout-per-shutdown-phase`. `spring.servlet.multipart` is raised to
            2 MB so the framework stops rejecting 1–2 MB icons before the service-level check runs.

Evidence `mvn -B test` in the backend container → 213 cases / 34 classes, 0 failures, 0 errors, 0
         skipped (212 before this round). `MerchantVoucherRedeemTest` needed a fixture change rather
         than a behaviour change: its `createPendingOrder` helper had been using the purchase entry to
         build a **seckill** order — exactly the hole closed above — so it now posts to
         `/voucher-orders/seckill-orders` and waits for the Stream consumer to persist the row. The
         one added case, `VoucherContractTest.purchaseEndpointRejectsSeckillVoucher`, pins the P0:
         `30007`, zero order rows, stock untouched. Re-count 2026-10-26: the current tree holds 41
         `*Test*.java` files, 39 classes with `@Test`, 242 `@Test` annotations — `7d9e5889` added five
         test classes (27 cases) with no surefire artifacts yet. 213 / 34 stays as the 2026-10-23 run
         record, not the current size; see `docs/90-evidence/00-metrics-and-evidence.md` §5.1.

Cost     Cancelling or refunding an ordinary voucher no longer touches `tb_seckill_voucher` or the
         seckill Redis keys, which is correct but means an inconsistent pre-existing row is no longer
         papered over. Removing the unconditional `repair()` means a genuinely broken migration now
         fails startup instead of being quietly skipped — that is the point. `PUT /shops/{id}` with
         no id now answers `10008` instead of the generic `99999`.

Commit   9e13a5d8

### D-017 · 2026-10-23 · chore
Symptom  The root layer had drifted. `AGENTS.md` carried an unnumbered `Docs & commits` section
         wedged between §2 and §3, so the numbered sequence broke in the middle and three references
         to "§3" pointed past it; `README.md` restated a hand-copied JUnit total that also lived in
         `backend/README.md` and in the §7 baseline table, which had been stale since the last test
         round; nothing checked that the backend map still matched the tree; and the READMEs
         presented the project as full-stack without saying which half is the graded artifact, while
         the frontend is a demonstration shell.

Change   Number the section as §3 and shift the rest to §4–§8, updating the three references
         (`AGENTS.md` twice, `MAINTENANCE.md` once) in both languages. Replace the hand-copied JUnit
         totals in the README, the map and the baseline table with "printed by the command", drop the
         frontend suite rows from the baseline, and label the table a dated snapshot. State in the
         READMEs and in `AGENTS.md`'s "Still open" list that the Vue client is a demo shell and the
         backend is the graded artifact; mark the frontend suites optional in `MAINTENANCE`'s L0.
         Add `scripts/check_backend_map.py` — packages, key classes, controller count and Flyway
         range against the tree — and run it as a third step in `docs-ci`. Add a one-line reading aid
         to this file's preamble.

Evidence `python3 scripts/check_bilingual.py` → 3 pairs in sync; `check_layout.py` → 5/5;
         `check_backend_map.py` → 6 package rows, schema at V16, rc=0. The same guard was provoked
         red on the sibling repository by editing its migration floor, then reverted. No Java,
         resource or migration file was touched, so the suites were not re-run.

Cost     The section numbers changed, so any note that quoted "§3" for the startup commands now has
         to say §4; the three in-repo references were updated, and `docs/` has none. Dropping the
         test totals from the baseline means the table no longer answers "how many tests" at a
         glance — it points at the command instead.

Commit   b3bd01db

### D-018 · 2026-10-25 · chore
Symptom  The docs were finished but unnumbered: `docs/02-interview/` sorted alphabetically, so file
         order disagreed with reading order. A first attempt numbered the headings instead of the
         file names, which added a second numbering system nobody asked for.
Change   Prefix every study file with its position in the reading routes (`01-resume/01..03`,
         `02-interview/00..13`) via `git mv`; rewrite every in-repo reference to the new names;
         re-align the README tree and list it in numeric order; add a one-line position card to each
         doc (route step, prerequisite, what you can do after it); drop the heading renumbering the
         first attempt introduced; retire the docs-freeze wording — the résumé was repaired once
         (header pointers only, body and figures untouched) and is stated as unchanged from
         2026-10-25 on; point that header at `90-evidence/figure-index.md`; correct the
         eight-vs-nine decision count in `docs/02-interview/03-database-design.md` and `docs/02-interview/12-self-check.md`.
Evidence `check_bilingual.py` → 3 pairs in sync; `check_layout.py` → 5/5; `check_backend_map.py` →
         6 package rows, schema at V16, rc=0. No relative `.md` link dangles; no old file name
         survives outside this record. No Java, resource or migration file was touched, so the
         suites were not re-run.
Cost     Paths changed, so any external bookmark or note that quotes one is stale; this record keeps
         the old names on purpose, and so does the bridge table in `docs/README.md`. The résumé is
         now held still by wording rather than by a rule file.

Commit   12a5b0f9

### D-019 · 2026-10-25 · guard
Symptom  Renaming the doc files broke no link this time, but nothing would have caught it if it
         had: no guard read the links inside `docs/`, and the tree in `docs/README.md` was kept
         by hand.
Change   Add `scripts/check_doc_links.py`: every relative Markdown link resolves, every
         `#anchor` matches a heading under GitHub slug rules, a label that names a `.md` file
         names the file it points at, and the tree block lists exactly the files on disk (both
         directions). Wire it into `docs-ci`; `file://` knowledge-base links are counted but
         not resolved, since CI does not have that machine's private tree.
Evidence Green on the current tree: 299 relative links, 127 `file://` counted, 19 docs files.
         Provoked red on all four classes with a throwaway document (missing target, missing
         heading, label pointing elsewhere, file absent from the tree), then removed it.
Cost     Adding a document now needs its tree entry in the same commit, and renaming one needs
         every link updated — which is the point. `frontend/` is skipped: its design guides are
         upstream copies, not ours.

Commit   d71088a5

### D-020 · 2026-10-25 · chore
Symptom  The 2026-10-25 read-only audit of this repository existed only as a one-off session report:
         P0 × 0, P1 × 6 (login-code brute force, seckill ignoring voucher status, Redis compensation
         inside the DB transaction, the one-order-per-user slot after cancel/refund, cache-breakdown
         mutex window, Flyway baseline), P2 × 22, plus a 17-row test-gap list, five next steps and the
         audit's own limitations. Nothing in the repository pointed a reader at it.
Change   File the report as `docs/audit/2026-10-25.md` — Chinese, matching the documented
         `docs/**`-stays-Chinese exception; a banner marks it a 2026-10-25 snapshot with every
         finding open (this audit produced no post-audit fixes); each finding keeps its severity,
         `file:line` evidence, impact and fix direction, prose compressed but nothing dropped and no
         number changed. Register the file in the `docs/README.md` tree and add a one-paragraph note
         that `audit/` is not learning material.
Evidence `check_layout.py`, `check_bilingual.py`, `check_backend_map.py` and `check_doc_links.py`
         all pass on the final tree; the new document adds no links, so the link graph is unchanged
         apart from the one new tree row.
Cost     Records only — no code path, no migration, no backend file touched. The audit's line
         numbers describe the 2026-10-25 working tree, not HEAD; the document says so in its banner.

Commit   41ec1faa

### D-021 · 2026-10-25 · fix
Symptom  The six P1s from the 2026-10-25 audit were all still open on the paths a user actually
         touches. A 6-digit login code had no send throttle and no per-code attempt cap, so one
         2-minute code accepted unlimited guesses at network speed. A delisted or expired seckill
         voucher kept selling: only `tb_seckill_voucher.end_time` was checked, never
         `tb_voucher.status`. Cancel/refund did two Redis writes inside the DB transaction — a
         rollback left stock inflated, a commit whose Redis call failed left it deflated, and a
         failure between INCR and SREM left the user's placeholder behind; none of the three had a
         compensation path. The `(user_id, voucher_id)` unique key counted terminal orders, so
         "cancel, then buy again" answered ORDER_DUPLICATE after the stock had already gone back.
         The cache mutex gave every waiter a fixed 500 ms window and then sent all of them to MySQL
         at once — the breakdown it exists to prevent. And Flyway ran `baseline-on-migrate: true`
         with no `baseline-version`, while V8/V11 are bare `ADD CONSTRAINT` / `CREATE INDEX`.
Change   (1) Login: `sendCode` takes a per-phone 60 s SETNX cooldown and a 5-per-hour INCR quota
         before a code is generated, and verification counts failures — the 5th wrong guess deletes
         the code and answers `10010 LOGIN_CODE_ATTEMPTS_EXCEEDED`, so one code allows five guesses
         instead of unlimited. A fresh code and a successful login both clear the counter.
         (2) Seckill: `seckillVoucher` reads `tb_voucher` and rejects any status but 1, answering
         VOUCHER_NOT_FOUND so "delisted" and "never existed" are indistinguishable to the caller.
         (3) Cancel/refund: the DB stock write stays inside the transaction; the Redis side moves to
         a new `SeckillStockCompensator` that runs after commit, does INCR + SREM in one Lua script
         (`lua/seckill_rollback.lua`) behind a per-order `SET NX` marker, retries three times with
         linear backoff, and pushes `orderId|voucherId|userId` to `seckill:rollback:dlq` when it
         still fails.
         (4) One-order-per-user: V17 replaces `uk_user_voucher` with `uk_user_voucher_active` over
         the generated column `active_voucher_id` (NULL for cancelled/refunded, and MySQL allows
         many NULLs in a unique index); the three one-order count queries skip
         `OrderStatus.UNIQUENESS_RELEASED_STATUSES`. The order row survives, so the cancelled list
         and reconciliation still see it.
         (5) Cache: waiters poll with exponential backoff plus full jitter up to one lock lease
         (10 s), then make one last lock attempt before degrading to a single DB read, instead of
         10 × 50 ms followed by a stampede.
         (6) Flyway: explicit `baseline-version: 11` — the last non-idempotent migration — and
         `clean-disabled: true`, with the two-path contract written into `MAINTENANCE.md`: an empty
         database runs V1–V17, a restored schema without history baselines at 11 and only replays
         V12–V17, all of which are `CREATE TABLE IF NOT EXISTS` or `information_schema`-guarded.
Evidence 242 tests, 0 failures, 0 errors against the compose MySQL 8 + Redis 7.2 (29 new:
         `SeckillStockCompensatorTest` 8, `UserServiceImplLoginCodeTest` 7,
         `SeckillVoucherStatusGateTest` 5, `CacheClientMutexWaitTest` 4,
         `VoucherOrderCancelRepurchaseTest` 3, `CacheGovernanceObservabilityTest` +2). The
         repurchase test buys, cancels, buys again over HTTP and again after refund, and asserts the
         cancelled row is still in the table — it can only pass with V17 applied, because the second
         insert would otherwise hit the old unique key. `check_layout.py`, `check_bilingual.py`,
         `check_backend_map.py` and `check_doc_links.py` pass.
         V17 shipped as a STORED generated column first and failed on the real database: MySQL 8
         rejects the table rebuild with `Error Code 1215 Cannot add foreign key constraint` because
         `tb_voucher_order` carries the two V8 foreign keys (first full run: 212 of 242 tests
         errored on `Migration V17 failed`). VIRTUAL only touches metadata and still supports a
         unique secondary index; the semantics were then checked directly — two terminal rows for
         one (user, voucher) accepted, a second active row rejected by
         `uk_user_voucher_active`.
Cost     The seckill path pays one extra `tb_voucher` read per request, which is the price of
         honouring the merchant's delist switch. A 60 s cooldown means a user who mistypes their
         phone number cannot resend immediately. The compensation is after-commit, not an outbox: a
         crash between the commit and the callback loses that one compensation, and the DLQ has no
         consumer, so recovery is a manual or scripted replay (both stated in the class javadoc).
         V17 drops `uk_user_voucher`, so "duplicate order" is no longer a database-level guarantee
         for terminal rows — that is the intent, and the remaining guarantee is "at most one active
         order". Flyway baseline 11 makes an in-place upgrade a documented contract instead of a
         guess: a schema older than V11 is not upgradable and must be restored or recreated.

Commit   7d9e5889

### D-022 · 2026-10-25 · chore
Symptom  `docs/02-interview/` held onboarding, depth, interview drill and self-check pages under one
         name, and its numbering followed neither the reading order nor the layout of the sibling
         AI-interview repository, so the two docs trees could not be learned the same way.
Change   Split it by use: `02-learn/` (onboarding), `03-deepen/` (depth), `04-interview/` (drill),
         `05-review/` (self-check); move the evidence index into `90-evidence/` as
         `00-metrics-and-evidence.md`; renumber `01-resume/` to `00`-`02`; name the `02-interview/`
         files by reading order. Nineteen files moved with `git mv`; no body text was rewritten.
Evidence `git show ebaae638 --name-status` lists nineteen `R100` renames and nothing else. The commit
         touched no `README`, so the root `README` pair and `docs/README.md` still named the old paths
         afterwards; the docs change that follows (D-023 to D-025) re-points them and runs the four
         repository guards, all of which pass.
Cost     Any bookmark or note that quotes an old path is stale, including two pointer lines inside the
         frozen résumé page (`docs/02-interview/07-project-pitch.md`, `docs/90-evidence/figure-index.md`);
         both lines are corrected in the same follow-up. The old names stay on purpose in this record,
         in `docs/README.md` and in the work log.

Commit   ebaae638

### D-023 · 2026-10-25 · docs
Symptom  The docs tree could not teach the project from zero: there was no requirements page, no
         page that walks one request to the database, no decision log and no glossary, so a reader
         had to reverse-engineer them from the architecture and interview pages.
Change   Add `docs/02-learn/00-requirements.md`, `05-code-walkthrough.md`,
         `docs/03-deepen/01-decision-log.md` and `04-glossary.md`; each states its evidence level
         (Verified / Inferred / Unconfirmed) and that it comes from static reading. Correct two rows of
         `docs/01-resume/02-resume-to-code-map.md` that presented `handleCachePenetration` and the
         logical-expire handler as production paths; the only production caller is
         `ShopServiceImpl#queryById` through `handleCacheBreakdownWithMutex`.
Evidence Class and method names were read from the current sources; no service was started and no
         test was run. The résumé main document is unchanged byte for byte.
Cost     The new pages describe behaviour by reading code, not by running it; anything about
         concurrency is a code-reading conclusion until the tests are run again.

Commit   d8af8231

### D-024 · 2026-10-25 · docs
Symptom  Nothing in the docs explained how AI tools were used to build and verify the project, and
         the interview pages could not answer "how do you make sure AI-written code is right" with
         anything checkable.
Change   Add `docs/03-deepen/05-ai-workflow.md`: a nine-step workflow and a review checklist (marked
         as recommended practice), five real defects with source or commit evidence, one simulated
         case with prompts and checks only, and a record card for the author's own future sessions.
Evidence The AI-tool traces come from commit subjects only (diffs not read). The repository holds no
         prompts or chat transcripts, so none are described or invented; whether a given defect was
         introduced or found by an AI tool is recorded as unconfirmed.
Cost     The page cannot say which tool wrote which part. Until the author logs real sessions, the
         "AI-assisted" story rests on the commit traces and on the defects that were found and fixed.

Commit   d8af8231

### D-025 · 2026-10-25 · docs
Symptom  Several pages presented Redisson's watchdog renewal as something this project relies on, but
         both `tryLock` calls pass an explicit lease, which disables the watchdog. The knowledge-base
         map pointed at note files only, and the development journey gave no hint that the repository
         holds two history lines (the original commits and a later regrouped set).
Change   Correct the watchdog statements in the QA bank, the pressure-test ladder, the architecture
         page, the bagu map and the decision log. Add a section-level table to the bagu map (project
         mechanism, note section, difference between note and project, used or not). Add
         `docs/90-evidence/01-git-history-map.md` and an evidence note at the top of
         `docs/02-learn/03-journey.md`; the stage bodies are untouched.
Evidence All knowledge-base `file://` links were resolved against the knowledge base in the uploaded
         archive; none is dangling. Section numbers come from note headings only, bodies were not read.
         Commit hashes and subjects come from the `git log` the author supplied; no dates and no diffs
         were seen, so month anchors in the journey stay unconfirmed.
Cost     Seven knowledge-base links whose target path contains a space are left as they were.

Commit   d8af8231

### D-026 · 2026-10-26 · docs
Symptom  The cross-repo docs pass found the README's §8 still saying the résumé's two pointers were
         never synced — contradicting §5 and D-022, which had already synced them the same day.
         The metrics page had five drifted line references in §6.2 and a §5.1 static count (36 / 34 /
         213) that `7d9e5889` had outgrown. And five old CHANGELOG entries from D-015 to D-019 had
         never had their commit hashes backfilled, breaking the "one bookkeeping commit per round"
         convention that D-014 states.
Change   Retire the §8 stale-conflict note by rewriting it to point at the D-022 sync. Re-check §6.2
         against the current sources (621→645, 647→671, 665→689, 449→470, 609→633). Add the
         2026-10-26 recount to §5.1 (41 files / 39 classes / 242 `@Test`; five classes and 27 cases
         added by 7d9e5889 have no surefire artifacts yet) as a note — 213 / 34 stays as the
         2026-10-23 run record, not the current size — and mirror the same note in the root README
         test row and inside D-016's Evidence, changing no historical number. Backfill the five
         missed commit hashes (D-015 05785035, D-016 9e13a5d8, D-017 b3bd01db, D-018 12a5b0f9,
         D-019 d71088a5). Port the stricter `check_doc_links.py` from the sibling repo (link text
         must name the target file; the docs tree is checked in both directions) while keeping this
         repo's knowledge-base `file://` handling and the 职责 heading.
Evidence Read-only throughout: no service, no test run, no artifact overwritten. The recount comes
         from `find`/`grep` over `backend/src/test/java`; the five new classes are named in the §5.1
         note. All four guards pass after the change.
Cost     The stricter link checker now matches the sibling repo, so a docs edit that passes there
         passes here — but both trees still guard paths and link text, not line numbers; the §6.2
         and metrics references will drift again with the next refactor.

Commit   5f02584c

### D-027 · 2026-10-29 · docs
Symptom  The docs still presented the pre-R9 baseline as current in several places: the unique
         key was described as the old full `uk_user_voucher`, the résumé map carried five
         highlights, the audit snapshot lived in the live tree, and the frontend test count was
         quoted without a passing run. Historical 34/213 artifacts and current 39/242 results
         were easy to mix.
Change   Rewrite the evidence index around the 2026-10-29 run (39 classes / 242 cases; entry P0,
         post-commit compensation, V17 active-only key). Rewrite the résumé review and map for
         the 4+4 R9 structure. Add `docs/03-deepen/06-ai-code-review.md` with risk classes,
         eight-step acceptance and an A/B scope. Add deep-dive topic 11, decision-log DL-16/17/18
         and QA Q34/Q35. Move `docs/audit/2026-10-25.md` and the pre-R9 evidence snapshot under
         `docs/archive/2026-10/`. Replace remaining old-key references with
         `uk_user_voucher_active`, keeping UF-004 explicitly historical.
Evidence Docs-only. `check_doc_links.py` resolves 363 links and lists 29 of 29 Markdown files;
         `check_layout.py`, `check_bilingual.py` and `check_backend_map.py` pass. Vitest was run
         on 2026-10-29: 9 files, 120 passed + 1 todo, 0 failed. No Java source, migration, test or
         preserved artifact was changed.
Cost     Old deep links into the audit snapshot move one hop. The evidence index must be refreshed
         after any rerun; historical 34/213 and 52/57 figures stay historical, not current.

Commit   45a808f (mirror of the source repository's change)

### D-028 · 2026-10-29 · docs
Symptom  The docs tree did not match the skeleton the sibling AI repo uses, and three gaps were
         interview-visible: there was no self-introduction page and no credibility-defence page,
         the evidence layer was a single 401-line file mixing index, test baseline, acceptance and
         performance, and a sixth top-level directory (`99-worklog/`) held process notes the target
         skeleton does not have. Worse, three live pages told the reader the verification artifacts
         were in the repository — `test-results/` is git-ignored, so an interviewer who cloned the
         repo would find nothing where the docs said to look.
Change   Move to the shared skeleton: `03-deepen/04-glossary.md` → `02-learn/06-glossary.md`,
         `05-ai-workflow` → `04-`, `06-ai-code-review` → `05-`; `04-interview/` renumbered to
         `01-project-pitch` … `05-must-memorize`, with a new `00-self-intro.md` (30s / 1min / 3min,
         shared verbatim with the sibling repo) and a new `06-credibility-defense.md`;
         `90-evidence/01-git-history-map.md` → `04-git-history.md`. Split the evidence page into
         `00` index / `01-test-baseline` / `02-evaluation` / `03-performance` and re-aim every `§N`
         reference in layers 01–05. Add `90-evidence/05-artifacts/{eval,archive}/README.md` (product
         slots; the artifacts stay uncommitted because that directory holds session-token files) and
         move `docs/archive/2026-10/` under `90-evidence/06-archive/2026-10/`. Retire
         `docs/99-worklog/`: its open items move to `03-deepen/03-boundaries.md` §7 (the two
         git-history unknowns, the UF-004 date conflict, doc-vs-source coverage, the nature of the
         concurrency evidence, three knowledge-base mapping unknowns, artifact sustainability, and
         the lost UF-003/005/006 register); its AI boundaries were already in `04-ai-workflow.md`.
         Facts corrected: `01-orientation.md` named V2 as the source of `uk_user_voucher_active`
         (V2 created the full key, V17 created the active one and dropped the full one); the glossary
         claimed three certainty levels (there are four) and its UF row now says UF-003/005/006 lost
         their content with `BACKEND_FREEZE.md`. Résumé cards each gained a 业务问题 line and a
         失败路径 table; the B-layer acceptance table now covers sign-in, upload, map/GEO,
         follow/profile and the merchant shop/voucher/order surfaces; journey headings dropped their
         month anchors into one table marked Unconfirmed (stage 8 excepted). Credibility:
         `03-pressure-test.md` P01 / P03 and the AI-discipline answer no longer claim the raw
         outputs are committed, and the `03-boundaries.md` artifact row says they are not.
         Root layer: the README pair's documentation tables and artifact note; the `AGENTS.md` /
         `AGENTS.zh-CN.md` pointer to the interview-expression discipline, which named a
         `docs/README.md` section that had stopped being about discipline.
Evidence Docs and the root file layer only. No Java source, migration, test, frontend source or
         preserved artifact was touched, and no test or benchmark was rerun — every figure keeps its
         snapshot date (2026-10-29 surefire and Vitest, 2026-10-06 smoke, 2026-10-01 Playwright,
         2026-09-19 latency). `check_doc_links.py` resolves 534 links and lists 35 of 35 Markdown
         files; `check_layout.py`, `check_bilingual.py` (3 pairs) and `check_backend_map.py`
         (6 package rows, schema at V17) pass. Counts were re-derived by command rather than copied:
         39 classes / 242 `@Test` annotations by static grep, and the ignore rules by
         `git check-ignore -v` (`.gitignore:52`, `backend/.gitignore:2`).
Cost     Deep links into the retired worklog and into `docs/archive/` move one hop, and the old
         `§N` anchors of the evidence index no longer exist — an external note quoting "§5.1" must
         be re-aimed at `01-test-baseline.md`. `00-self-intro.md` is duplicated verbatim in the
         sibling repo and no guard compares the two, so editing one side alone is silent drift.
         The artifacts are still uncommitted, so a fresh clone still cannot verify any figure
         offline; the desensitised-summary proposal stays unexecuted.

Commit   45a808f (mirror of the source repository's change)
