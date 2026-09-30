package com.github.davidmoten.fsm.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.Test;

import com.github.davidmoten.fsm.graph.NodeOptions;
import com.github.davidmoten.fsm.model.order.CreateOrder;
import com.github.davidmoten.fsm.model.order.Order;
import com.github.davidmoten.fsm.model.order.SubmitOrder;

public final class InitialTransitionDefinitionTest {

    private static final String INITIAL = "Initial";

    /**
     * A definition whose only transition is the creation transition
     * {@code Initial -> Created}. The target state is registered solely by
     * {@link State#initial()} so the regression only passes when
     * {@code addInitialTransition} closes the definition set.
     */
    private static StateMachineDefinition<Order> creationOnlyDefinition() {
        StateMachineDefinition<Order> m = StateMachineDefinition.create(Order.class);
        State<Order, CreateOrder> created = new State<Order, CreateOrder>(m, "Created",
                CreateOrder.class);
        created.initial();
        return m;
    }

    @Test
    public void testCreationTransitionTargetEntersDefinitionStates() {
        StateMachineDefinition<Order> m = creationOnlyDefinition();
        assertEquals(1, m.transitions().size());
        // the definition set (as projected by the shared createGraph path) must
        // contain the implicit initial pseudostate once and the target state
        List<String> nodes = graphNodeIds(m.graphml(g -> NodeOptions.defaultInstance(), false));
        assertTrue("target must be in states set so graph renderers resolve it",
                nodes.contains("Created"));
        assertTrue(nodes.contains(INITIAL));
        assertEquals("initial pseudostate must not be counted twice", 2, nodes.size());
    }

    @Test
    public void testPlantUmlContainsCreationEdgeAndTargetNode() {
        StateMachineDefinition<Order> m = creationOnlyDefinition();
        String puml = m.plantuml(g -> NodeOptions.defaultInstance(), true);
        // one creation edge, rendered from the implicit initial pseudostate
        assertEquals(1, countOccurrences(puml, "-->"));
        assertTrue(puml.contains("[*] --> Created : CreateOrder"));
        // no duplicated explicit Initial node
        assertFalse(puml.contains(INITIAL));
        // target appears as the edge destination node exactly once
        assertEquals(1, countOccurrences(puml, " --> Created : "));
    }

    @Test
    public void testGraphmlContainsCreationNodeAndEdge() {
        StateMachineDefinition<Order> m = creationOnlyDefinition();
        String graphml = m.graphml(g -> NodeOptions.defaultInstance(), false);
        assertEquals(2, countXmlElement(graphml, "node"));
        assertEquals(1, countXmlElement(graphml, "edge"));
        assertTrue("initial pseudostate node present once",
                graphml.contains("<node id=\"Initial\">"));
        assertTrue("creation target present once",
                graphml.contains("<node id=\"Created\">"));
        assertEquals(1, countOccurrences(graphml, "source=\"Initial\" target=\"Created\""));
    }

    @Test
    public void testHtmlDocumentsCreationTargetAndEdge() {
        StateMachineDefinition<Order> m = creationOnlyDefinition();
        String html = m.documentationHtml();
        // the two definition nodes each appear as a row header and a column header
        assertEquals(2, countOccurrences(html, "<th>Created</th>"));
        assertEquals(2, countOccurrences(html, "<th>Initial</th>"));
        // exactly one transition cell and it is the Initial -> Created edge
        assertEquals(1, countOccurrences(html, "class=\"transition\""));
        // target also present in the States section
        assertTrue(html.contains("<p class=\"state\"><b>Created</b>"));
    }

    @Test
    public void testGeneratorEmitsCompilableJavaForCreationOnlyDefinition() throws Exception {
        StateMachineDefinition<Order> m = creationOnlyDefinition();
        File dir = new File("target/initial-transition-gen");
        deleteDirectory(dir);
        m.generateClasses(dir, "generated.order");

        File stateMachineFile = new File(dir, "generated/order/OrderStateMachine.java");
        File behaviourFile = new File(dir, "generated/order/OrderBehaviour.java");
        File behaviourBaseFile = new File(dir, "generated/order/OrderBehaviourBase.java");
        assertTrue(stateMachineFile.exists());
        assertTrue(behaviourFile.exists());
        assertTrue(behaviourBaseFile.exists());

        String stateMachine = readFile(stateMachineFile);
        // enum contains the implicit INITIAL pseudostate and the business state,
        // matching the graph renderers (2 nodes)
        assertTrue(stateMachine.contains("INITIAL,\n        CREATED;"));
        // one transition branch for the creation edge
        assertEquals(1, countOccurrences(stateMachine, "state == State.INITIAL"));
        // creation onEntry signature (no entity instance available yet)
        assertTrue(stateMachine.contains("behaviour.onEntry_Created(this, this.id, (CreateOrder) event"));

        compileGeneratedSources(dir, stateMachineFile, behaviourFile, behaviourBaseFile);
    }

    @Test
    public void testDuplicateInitialTransitionIsRejectedAndDefinitionUnchanged() {
        StateMachineDefinition<Order> m = StateMachineDefinition.create(Order.class);
        State<Order, CreateOrder> created = new State<Order, CreateOrder>(m, "Created",
                CreateOrder.class);
        created.initial();
        try {
            m.addInitialTransition(created);
            fail("expected duplicate creation transition to be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("the transition already exists: Initial -> Created", e.getMessage());
        }
        // failure recovery: rejected registration leaves no extra edge or node
        assertEquals(1, m.transitions().size());
        String puml = m.plantuml(g -> NodeOptions.defaultInstance(), false);
        assertEquals(1, countOccurrences(puml, "-->"));
        String graphml = m.graphml(g -> NodeOptions.defaultInstance(), false);
        assertEquals(1, countXmlElement(graphml, "edge"));
        assertEquals(2, countXmlElement(graphml, "node"));
    }

    @Test
    public void testTwoCreationTransitionsAreDistinctEdges() {
        // symmetric entry: both states enter only through creation transitions
        StateMachineDefinition<Order> m = StateMachineDefinition.create(Order.class);
        State<Order, CreateOrder> created = new State<Order, CreateOrder>(m, "Created",
                CreateOrder.class);
        State<Order, SubmitOrder> submitted = new State<Order, SubmitOrder>(m, "Submitted",
                SubmitOrder.class);
        created.initial();
        submitted.initial();
        assertEquals(2, m.transitions().size());
        String puml = m.plantuml(g -> NodeOptions.defaultInstance(), false);
        assertEquals(2, countOccurrences(puml, "-->"));
        assertTrue(puml.contains("[*] --> Created : CreateOrder"));
        assertTrue(puml.contains("[*] --> Submitted : SubmitOrder"));
        String graphml = m.graphml(g -> NodeOptions.defaultInstance(), false);
        assertEquals(2, countXmlElement(graphml, "edge"));
        assertEquals(3, countXmlElement(graphml, "node"));
        String html = m.documentationHtml();
        assertEquals(2, countOccurrences(html, "class=\"transition\""));
    }

    @Test
    public void testEmptyDefinitionGeneratesConsistentArtifacts() throws Exception {
        StateMachineDefinition<Order> m = StateMachineDefinition.create(Order.class);
        // no transitions at all: zero nodes, zero edges in every renderer
        assertEquals(0, m.transitions().size());
        String puml = m.plantuml(g -> NodeOptions.defaultInstance(), false);
        assertFalse(puml.contains("-->"));
        assertFalse(puml.contains(INITIAL));
        String graphml = m.graphml(g -> NodeOptions.defaultInstance(), false);
        assertEquals(0, countXmlElement(graphml, "node"));
        assertEquals(0, countXmlElement(graphml, "edge"));
        String html = m.documentationHtml();
        assertFalse(html.contains("class=\"transition\""));

        File dir = new File("target/initial-transition-gen-empty");
        deleteDirectory(dir);
        m.generateClasses(dir, "generated.orderempty");
        File stateMachineFile = new File(dir,
                "generated/orderempty/OrderStateMachine.java");
        String stateMachine = readFile(stateMachineFile);
        // an enum with no constants is still valid Java; no creation factory
        assertFalse(stateMachine.contains("State.INITIAL"));
        compileGeneratedSources(dir, stateMachineFile, //
                new File(dir, "generated/orderempty/OrderBehaviour.java"),
                new File(dir, "generated/orderempty/OrderBehaviourBase.java"));
    }

    @Test
    public void testDuplicateNormalTransitionIsStillRejected() {
        StateMachineDefinition<Order> m = StateMachineDefinition.create(Order.class);
        State<Order, CreateOrder> created = new State<Order, CreateOrder>(m, "Created",
                CreateOrder.class);
        State<Order, SubmitOrder> submitted = new State<Order, SubmitOrder>(m, "Submitted",
                SubmitOrder.class);
        created.to(submitted);
        try {
            created.to(submitted);
            fail("expected duplicate transition to be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("the transition already exists: Created -> Submitted", e.getMessage());
        }
        assertEquals(1, m.transitions().size());
    }

    private static List<String> graphNodeIds(String graphml) {
        Pattern pattern = Pattern.compile("<node id=\"([^\"]+)\">");
        List<String> names = new ArrayList<String>();
        Matcher matcher = pattern.matcher(graphml);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static int countOccurrences(String content, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(fragment, index)) != -1) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static int countXmlElement(String xml, String element) {
        // nodes/edges always carry attributes in GraphmlWriter so "<name " is exact
        return countOccurrences(xml, "<" + element + " ");
    }

    private static String readFile(File file) throws Exception {
        return String.join("\n", java.nio.file.Files.readAllLines(file.toPath()));
    }

    private static void deleteDirectory(File dir) throws Exception {
        if (!dir.exists()) {
            return;
        }
        java.nio.file.Files.walk(dir.toPath()) //
                .sorted((a, b) -> b.compareTo(a)) //
                .forEach(path -> {
                    try {
                        java.nio.file.Files.delete(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * Compiles the generated sources with the in-process JDK compiler so the
     * evidence does not depend on a shell command or an absolute fixture path.
     */
    private static void compileGeneratedSources(File dir, File... files) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue("JDK compiler required for the generated-sources check", compiler != null);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null,
                null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(files);
            File output = new File(dir, "compiled");
            output.mkdirs();
            List<String> options = new ArrayList<String>();
            options.add("-classpath");
            options.add(System.getProperty("java.class.path"));
            options.add("-d");
            options.add(output.getAbsolutePath());
            boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .collect(Collectors.toList());
            assertTrue("generated sources must compile: " + errors, errors.isEmpty());
            assertTrue(ok);
        }
    }

}
