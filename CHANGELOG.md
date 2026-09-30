# Changelog

## Unreleased

### Fix: target of an initial (creation) transition missing from the definition set

#### Structural choice

`StateMachineDefinition` is the single owner of both `transitions` and the
`states` set. `Generator` derives runtime artifacts from `transitions`, while
`documentationHtml()` and the shared `createGraph()` used by both the GraphML
and PlantUML renderers resolve transition endpoints by looking them up in
`states`. The root cause is therefore repaired at the single registration
choke point `StateMachineDefinition.addInitialTransition(other)`:

- the target state `other` is added to `states` together with the implicit
  `Initial` pseudostate, restoring the invariant that both endpoints of every
  registered transition belong to the definition set;
- a creation transition identical to one already registered is rejected with
  the same `IllegalArgumentException` ("the transition already exists: ...")
  used by `addTransition`, and the check happens before any mutation so a
  rejected registration leaves the definition unchanged.

No renderer or generator was patched individually: the HTML transition table,
`GraphmlWriter` and `PlantUmlWriter` all consume the same now-closed
definition. The `Initial` pseudostate is still stored once (the container is
a `Set`) and is never converted into a business state — it stays filtered out
of the generated enum/runtime states (`Generator.states()` and the behaviour
methods) and out of the PlantUML node list (rendered as the implicit `[*]`
entry node).

#### Previous coverage gap

The existing `StateMachineTest` registers a creation transition but only
prints the PlantUML output and always creates every state via
`createState`, which independently adds to `states`. Nothing exercised a
state that enters the definition *only* through `initial()`. In that
situation `createGraph()` produced a `GraphEdge` with a null `to` node, so
both GraphML and PlantUML failed with a `NullPointerException`, the HTML
states section/transition matrix omitted the target, while the generated
runtime class still emitted the enum constant and signal branch because it
derives states from `transitions`. The four artifacts were therefore
inconsistent for the same definition.

#### Most dangerous counterexamples and the tests that pin them

All evidence is in
`state-machine-generator/src/test/java/com/github/davidmoten/fsm/model/InitialTransitionDefinitionTest.java`
(domain fixtures under the `.../model/order/` package):

- Creation-only definition (`Initial -> Created`, target registered solely by
  `State.initial()`):
  - `testCreationTransitionTargetEntersDefinitionStates` — definition set
    contains `Initial` exactly once plus `Created` (pseudostate not double
    counted).
  - `testPlantUmlContainsCreationEdgeAndTargetNode` — one `-->` edge emitted
    as `[*] --> Created : CreateOrder`; no explicit `Initial` node.
  - `testGraphmlContainsCreationNodeAndEdge` — two `<node>` elements
    (`Initial`, `Created`) and one `<edge source="Initial" target="Created">`.
  - `testHtmlDocumentsCreationTargetAndEdge` — two matrix nodes and exactly
    one `class="transition"` cell for the creation edge.
  - `testGeneratorEmitsCompilableJavaForCreationOnlyDefinition` — generated
    enum is `INITIAL, CREATED;`, exactly one `state == State.INITIAL` branch
    with the creation `onEntry` signature, and the three generated sources are
    compiled in-process with the JDK `JavaCompiler` (no shell/network/path
    assumptions).
- Symmetric entry: `testTwoCreationTransitionsAreDistinctEdges` — two
  independently-created target states give two creation edges and three nodes
  consistently in PlantUML, GraphML and HTML.
- Failure recovery:
  `testDuplicateInitialTransitionIsRejectedAndDefinitionUnchanged` —
  duplicate creation transition throws the same pre-existing exception type
  and message style, with no extra edge/node afterwards.
  `testDuplicateNormalTransitionIsStillRejected` keeps the ordinary
  `addTransition` guard working on the boundary path.
- Empty definition: `testEmptyDefinitionGeneratesConsistentArtifacts` — zero
  transitions means zero nodes/edges in PlantUML, GraphML and HTML, no
  `Initial` pseudostate appears at all, and the generated sources (including a
  constant-less `State` enum) still compile.

#### Public behaviour deliberately preserved

- DSL and API signatures are unchanged (`State.initial()`,
  `addInitialTransition`, `createState`, `plantuml`, `graphml`,
  `documentationHtml`, `generateClasses`).
- Duplicate-transition error type and message wording stay identical to the
  existing `addTransition` convention.
- The `Initial` pseudostate remains implicit for business-state purposes:
  filtered out of generated enum/business states and PlantUML nodes, while
  still rendered once as the GraphML entry node / `[*]` entry edge source.
- Existing assertions and fixtures were left untouched; the fix does not
  special-case any fixture name or literal.
