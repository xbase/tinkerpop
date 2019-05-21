package org.apache.tinkerpop.gremlin.tinkergraph.mytest;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.Test;

public class Test1 {

    @Test
    public void test1(){
        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        Vertex marko = g.addV("person").property("name","marko").property("age",29).next();
        Vertex lop = g.addV("software").property("name","lop").property("lang","java").next();
        g.addE("created").from(marko).to(lop).property("weight",0.6d).iterate();

        GraphTraversal<Vertex, Object> values = g.V().has("name", "marko").out("created").values("name");
        for (GraphTraversal<Vertex, Object> it = values; it.hasNext(); ) {
            Object obj = it.next();
            System.out.println(obj);
        }
    }
}
