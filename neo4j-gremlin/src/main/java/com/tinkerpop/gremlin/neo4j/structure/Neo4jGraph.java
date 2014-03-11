package com.tinkerpop.gremlin.neo4j.structure;

import com.tinkerpop.gremlin.neo4j.process.map.Neo4jGraphStep;
import com.tinkerpop.gremlin.process.computer.GraphComputer;
import com.tinkerpop.gremlin.process.graph.DefaultGraphTraversal;
import com.tinkerpop.gremlin.process.graph.GraphTraversal;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Transaction;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.util.ElementHelper;
import com.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationConverter;
import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.kernel.GraphDatabaseAPI;
import javax.transaction.TransactionManager;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class Neo4jGraph implements Graph {
    private GraphDatabaseService rawGraph;
    private static final String INDEXED_KEYS_POSTFIX = ":indexed_keys";

    protected final ThreadLocal<org.neo4j.graphdb.Transaction> threadLocalTx = new ThreadLocal<org.neo4j.graphdb.Transaction>() {
        protected org.neo4j.graphdb.Transaction initialValue() {
            return null;
        }
    };

    protected final ThreadLocal<Boolean> checkElementsInTransaction = new ThreadLocal<Boolean>() {
        protected Boolean initialValue() {
            return false;
        }
    };

    private ThreadLocal<Neo4jTransaction> neo4jTransaction = new ThreadLocal<Neo4jTransaction>() {
        protected Neo4jTransaction initialValue() {
            return new Neo4jTransaction(Neo4jGraph.this);
        }
    };

    protected final TransactionManager transactionManager;
    private final ExecutionEngine cypher;

    private Neo4jGraph(final GraphDatabaseService rawGraph) {
        this.rawGraph = rawGraph;
        transactionManager = ((GraphDatabaseAPI) rawGraph).getDependencyResolver().resolveDependency(TransactionManager.class);
        cypher = new ExecutionEngine(rawGraph);

        // todo: indices were established in init
        // init();
    }

    private Neo4jGraph(final Configuration configuration) {
        try {
            final String directory = configuration.getString("gremlin.neo4j.directory");
            final GraphDatabaseBuilder builder = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(directory);

            final Map neo4jSpecificConfig = ConfigurationConverter.getMap(configuration.subset("gremlin.neo4j.conf"));
            this.rawGraph = builder.setConfig(neo4jSpecificConfig).newGraphDatabase();

            transactionManager = ((GraphDatabaseAPI) rawGraph).getDependencyResolver().resolveDependency(TransactionManager.class);
            cypher = new ExecutionEngine(rawGraph);

            // todo: indices were established in init
            // init();

        } catch (Exception e) {
            if (this.rawGraph != null)
                this.rawGraph.shutdown();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Open a new {@link Neo4jGraph} instance.
     *
     * @param configuration the configuration for the instance
     * @param <G>           the {@link com.tinkerpop.gremlin.structure.Graph} instance
     * @return a newly opened {@link com.tinkerpop.gremlin.structure.Graph}
     */
    public static <G extends Graph> G open(final Optional<Configuration> configuration) {
        // todo: check null on configuration and validate what's passed in
        return (G) new Neo4jGraph(configuration.get());
    }

    public static <G extends Graph> G open(final GraphDatabaseService rawGraph) {
        return (G) new Neo4jGraph(rawGraph);
    }

    @Override
    public Vertex addVertex(final Object... keyValues) {
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        if (ElementHelper.getIdValue(keyValues).isPresent())
            throw Vertex.Exceptions.userSuppliedIdsNotSupported();

        final String label = ElementHelper.getLabelValue(keyValues).orElse(Element.DEFAULT_LABEL);

        this.tx().readWrite();
        final Neo4jVertex vertex = new Neo4jVertex(this.rawGraph.createNode(DynamicLabel.label(label)), this);
        ElementHelper.attachProperties(vertex, keyValues);
        return vertex;
    }

    @Override
    public GraphTraversal<Vertex, Vertex> V() {
        final GraphTraversal traversal = new DefaultGraphTraversal<Object, Vertex>();
        traversal.addStep(new Neo4jGraphStep(traversal, Vertex.class, this));
        return traversal;
    }

    @Override
    public GraphTraversal<Edge, Edge> E() {
        final GraphTraversal traversal = new DefaultGraphTraversal<Object, Edge>();
        traversal.addStep(new Neo4jGraphStep(traversal, Edge.class, this));
        return traversal;
    }

    @Override
    public GraphComputer compute() {
        throw Graph.Exceptions.graphComputerNotSupported(); // todo: fix later
    }

    @Override
    public Transaction tx() {
        return neo4jTransaction.get();
    }

    @Override
    public <M extends Memory> M memory() {
        throw Graph.Exceptions.memoryNotSupported(); // todo: fix later
    }

    @Override
    public void close() throws Exception {
        // need to close any dangling transactions
        // todo: does this need to be done across threads to keep shutdown fast???
        this.tx().close();
        if (this.rawGraph != null)
            this.rawGraph.shutdown();
    }

    public String toString() {
        return StringFactory.graphString(this, rawGraph.toString());
    }

    public Features getFeatures() {
        return new Neo4jGraphFeatures();
    }

    public static class Neo4jGraphFeatures implements Features {
        @Override
        public GraphFeatures graph() {
            return new GraphFeatures() {
                @Override
                public boolean supportsMemory() {
                    return false;    // todo: temporary...doesn't neo4j support graph properties?
                }

                @Override
                public boolean supportsComputer() {
                    return false;  // todo: temporary...
                }

                @Override
                public MemoryFeatures memory() {
                    return new Neo4jMemoryFeatures();  // todo: temporary
                }
            };
        }

        @Override
        public VertexFeatures vertex() {
            return new Neo4jVertexFeatures();
        }

        @Override
        public EdgeFeatures edge() {
            return new Neo4jEdgeFeatures();
        }

        public static class Neo4jVertexFeatures implements VertexFeatures {
            @Override
            public VertexAnnotationFeatures annotations() {
                return new Neo4jVertexAnnotationFeatures();
            }

            @Override
            public boolean supportsUserSuppliedIds() {
                return false;
            }

            @Override
            public VertexPropertyFeatures properties() {
                return new Neo4jVertexPropertyFeatures();
            }
        }

        public static class Neo4jEdgeFeatures implements EdgeFeatures {
            @Override
            public boolean supportsUserSuppliedIds() {
                return false;
            }

            @Override
            public EdgePropertyFeatures properties() {
                return new Neo4jEdgePropertyFeatures();
            }
        }
        
        public static class Neo4jVertexPropertyFeatures implements VertexPropertyFeatures {
            @Override
            public boolean supportsMapValues() {
                return false;  
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;  
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;  
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;  
            }
        }

        public static class Neo4jEdgePropertyFeatures implements EdgePropertyFeatures {
            @Override
            public boolean supportsMapValues() {
                return false;  
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;  
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;  
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;  
            }
        }

        public static class Neo4jMemoryFeatures implements MemoryFeatures {
            @Override
            public boolean supportsBooleanValues() {
                return false;
            }

            @Override
            public boolean supportsDoubleValues() {
                return false;
            }

            @Override
            public boolean supportsFloatValues() {
                return false;
            }

            @Override
            public boolean supportsIntegerValues() {
                return false;
            }

            @Override
            public boolean supportsLongValues() {
                return false;
            }

            @Override
            public boolean supportsMapValues() {
                return false;
            }

            @Override
            public boolean supportsMetaProperties() {
                return false;
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;
            }

            @Override
            public boolean supportsPrimitiveArrayValues() {
                return false;
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;
            }

            @Override
            public boolean supportsStringValues() {
                return false;
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;
            }
        }

        public static class Neo4jVertexAnnotationFeatures implements VertexAnnotationFeatures {
            @Override
            public boolean supportsBooleanValues() {
                return false;
            }

            @Override
            public boolean supportsDoubleValues() {
                return false;
            }

            @Override
            public boolean supportsFloatValues() {
                return false;
            }

            @Override
            public boolean supportsIntegerValues() {
                return false;
            }

            @Override
            public boolean supportsLongValues() {
                return false;
            }

            @Override
            public boolean supportsMapValues() {
                return false;
            }

            @Override
            public boolean supportsMetaProperties() {
                return false;
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;
            }

            @Override
            public boolean supportsPrimitiveArrayValues() {
                return false;
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;
            }

            @Override
            public boolean supportsStringValues() {
                return false;
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;
            }
        }
    }

    public GraphDatabaseService getRawGraph() {
        return this.rawGraph;
    }

    public Iterator<Map<String,Object>> query(final String query, final Map<String,Object> params) {
        return cypher.execute(query,null == params ? Collections.<String,Object>emptyMap() : params).iterator();
    }
}
