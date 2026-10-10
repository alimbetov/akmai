# Semantic Domain Corpus v2 — Stage 2 review

Status: TARGET / review record for `feature/semantic-domain-corpus-v2`.

## Scope

Stage 2 now contains three balanced +100 rounds for the six operational domains:

- `energy_utilities`: Batch A/B/C = +300, 324 total;
- `oil_gas_mining`: Batch A/B/C = +300, 324 total;
- `manufacturing`: Batch A/B/C = +300, 324 total;
- `construction_real_estate`: Batch A/B/C = +300, 324 total;
- `transport_logistics`: Batch A/B/C = +300, 324 total;
- `agriculture_food`: Batch A/B/C = +300, 324 total.

Reference domains remain complete at:

- `finance_banking`: +400, 424 total;
- `insurance`: +400, 424 total.

The English canonical corpus represented by this branch now targets 2,984 concepts: 384 baseline concepts plus 2,600 additions.

## Review method

Every supplement is loaded through the production `EnglishSemanticConceptCatalog` normalization and must satisfy:

1. 2–6 canonical words;
2. globally unique canonical phrase after normalization;
3. stable domain/subdomain-derived ID;
4. valid domain/subdomain ownership;
5. English preferred/alias surface ownership rules;
6. ambiguity registry referential integrity.

`SemanticCorpusNearDuplicateAuditor` remains advisory. Lexical similarity produces review candidates but never performs automatic merges.

## Exact-duplicate defect discovered during Batch C

While preparing Batch C, review of the already committed Stage 2 A/B files found that several Batch B entries repeated Batch A canonical phrases exactly. This is a real corpus defect because `EnglishSemanticConceptCatalog` intentionally rejects global normalized duplicates.

The issue was fixed before declaring the 2,984-concept state admissible. Quotas were preserved by replacing repeated entries with distinct professional concepts rather than weakening the uniqueness gate.

### Energy / utilities cleanup

Repeated A/B concepts included examples such as:

- `heat rate curve`;
- `forced outage rate`;
- `capacity factor analysis`;
- `minimum stable generation`;
- `startup cost model`;
- `black start capability`;
- `transmission capacity planning`;
- `locational marginal pricing`;
- `market clearing price`;
- `wind resource assessment`;
- `battery degradation model`.

Batch B now uses distinct concepts such as `incremental heat rate`, `equivalent forced outage`, `transmission transfer capability`, `nodal market settlement`, `wind resource characterization`, and `battery lifetime model`.

### Oil / gas / mining cleanup

Repeated A/B concepts included `seismic interpretation workflow`, `drilling mud program`, `well integrity management`, `pipeline capacity analysis`, `line pack management`, `pipeline pressure control`, `custody transfer measurement`, `refinery crude slate`, `refinery margin analysis`, `turnaround scope planning`, `crusher throughput optimization`, and `tailings storage facility`.

They were replaced by distinct concepts such as `seismic inversion analysis`, `drilling fluid performance`, `pipeline throughput capacity`, `line pack optimization`, `custody transfer metering`, `refinery feedstock slate`, `turnaround workpack planning`, and `tailings facility operations`.

### Manufacturing / construction cross-batch cleanup

- Manufacturing Batch B no longer reuses cross-domain canonical surfaces `sales operations planning`, `available to promise`, and `capable to promise`; they were replaced with manufacturing-specific concepts.
- Construction Batch B no longer repeats `lease expiry profile`; it now uses `lease event schedule`.

### Transport / logistics cleanup

Batch B repeated a significant set of Batch A concepts, including freight, warehouse, fleet and supply-chain surfaces such as `freight lane analysis`, `warehouse slotting optimization`, `dock door scheduling`, `vehicle lifecycle cost`, `driver hours compliance`, `fleet emissions monitoring`, `safety stock optimization`, and `supply chain visibility`.

These were replaced with independent concepts such as `lane profitability analysis`, `dynamic slotting policy`, `dock appointment optimization`, `vehicle economic life`, `hours of service audit`, `fleet carbon intensity`, `safety stock segmentation`, and `shipment milestone visibility`.

### Agriculture / food cleanup

Agriculture Batch B contained the largest repeated A/B cluster. Repeated concepts included `crop rotation planning`, `soil moisture monitoring`, `feed conversion ratio`, `body condition scoring`, `thermal process validation`, `pasteurization process control`, `hazard analysis plan`, `critical control point`, `environmental monitoring program`, `supplier food safety`, and `food fraud vulnerability`.

They were replaced with distinct concepts including `crop sequence design`, `soil water profile`, `feed efficiency benchmark`, `condition score distribution`, `thermal lethality calculation`, `pasteurization hold time`, `food hazard register`, `critical limit verification`, `zone sampling strategy`, `supplier hazard review`, and `authenticity risk assessment`.

## Batch C coverage

Batch C adds another 25 concepts per existing subdomain. Representative additions include:

- Energy: `state estimation analysis`, `dynamic line rating`, `scarcity pricing mechanism`, `battery cycle aging`;
- Oil/gas/mining: `reservoir material balance`, `pipeline transient analysis`, `crude compatibility analysis`, `geostatistical block estimation`;
- Manufacturing: `drum buffer rope`, `layered process audit`, `vibration spectrum analysis`, `control valve performance`;
- Construction/real estate: `pile load testing`, `tenant concentration risk`, `asset condition index`, `integrated master schedule`;
- Transport/logistics: `carrier capacity commitment`, `inventory dwell time`, `driver fatigue management`, `demand sensing process`;
- Agriculture/food: `soil salinity monitoring`, `lactation curve analysis`, `fermentation yield monitoring`, `food authenticity testing`.

## Explicit ambiguity registrations

Existing explicit generic-surface ambiguity registrations remain in force, including:

- `preventive maintenance`;
- `capacity planning`;
- `settlement reconciliation`;
- `reserve requirement`;
- `risk appetite`;
- `asset management`;
- `risk assessment`;
- `risk model`;
- `decision making`.

## Release gates

Stage 2 Batch C is admissible only when the current branch head passes:

1. canonical normalized uniqueness;
2. multi-word phrase constraints;
3. expected 2,984 total concepts;
4. 324 concepts for each Stage 2 domain and 81 per Stage 2 subdomain;
5. ambiguity registry referential integrity;
6. English surface ownership checks;
7. near-duplicate review with no unresolved merge-class candidates;
8. repository CI;
9. Retrieval Quality Gate;
10. Retrieval Storage Final Benchmark;
11. Production Image Build.

Until those gates complete successfully, the 2,984-concept state is implemented but provisional.

## Next expansion

After Stage 2 Batch C is green and consolidated, complete Batch D (+100 each) for these six operational domains. That will bring all eight expanded operational/reference domains to the +400 target before moving to the science/technology domain group or multilingual RU → KK transfer.
