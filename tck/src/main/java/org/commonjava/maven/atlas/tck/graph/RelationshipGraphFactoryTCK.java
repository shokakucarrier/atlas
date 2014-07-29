package org.commonjava.maven.atlas.tck.graph;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.List;

import org.commonjava.maven.atlas.graph.RelationshipGraph;
import org.commonjava.maven.atlas.graph.RelationshipGraphException;
import org.commonjava.maven.atlas.graph.ViewParams;
import org.commonjava.maven.atlas.graph.rel.ParentRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.spi.RelationshipGraphConnectionException;
import org.commonjava.maven.atlas.graph.traverse.RelationshipGraphTraversal;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.junit.Test;

public abstract class RelationshipGraphFactoryTCK
    extends AbstractSPI_TCK
{

    @Test
    public void openStoreDeleteAndReopen()
        throws Exception
    {
        final ProjectVersionRef r = new ProjectVersionRef( "org.test", "root", "1" );
        final ProjectVersionRef p = new ProjectVersionRef( "org.test", "parent", "1.0" );
        final ProjectVersionRef c = new ProjectVersionRef( "org.test", "child", "1.0" );

        final URI source = sourceURI();

        final String wsid = newWorkspaceId();

        final RelationshipGraph child = openGraph( new ViewParams( wsid, c ), true );

        child.storeRelationships( new ParentRelationship( source, c, p ) );

        openGraph( new ViewParams( wsid, p ), true ).storeRelationships( new ParentRelationship( source, p, r ) );

        RelationshipGraph graph = openGraph( new ViewParams( wsid, r ), true );
        graph.storeRelationships( new ParentRelationship( source, r ) );

        //        final Thread t = new Thread( new DelayTraverseRunnable( graph ) );
        //        t.setDaemon( true );
        //        t.start();

        try
        {
            graphFactory().deleteWorkspace( wsid );

            graph = openGraph( new ViewParams( wsid, c ), true );
            assertThat( graph, notNullValue() );

            graph.storeRelationships( new ParentRelationship( source, c, p ) );
        }
        finally
        {
            //            t.interrupt();
        }
    }

    public static final class DelayTraverseRunnable
        implements Runnable
    {
        private final RelationshipGraph graph;

        DelayTraverseRunnable( final RelationshipGraph graph )
        {
            this.graph = graph;
        }

        @Override
        public void run()
        {
            try
            {
                graph.traverse( new RelationshipGraphTraversal()
                {
                    @Override
                    public boolean traverseEdge( final ProjectRelationship<?> relationship,
                                                 final List<ProjectRelationship<?>> path )
                    {
                        try
                        {
                            Thread.sleep( 2000 );
                        }
                        catch ( final InterruptedException e )
                        {
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public void startTraverse( final RelationshipGraph graph )
                        throws RelationshipGraphConnectionException
                    {
                    }

                    @Override
                    public boolean preCheck( final ProjectRelationship<?> relationship,
                                             final List<ProjectRelationship<?>> path )
                    {
                        return true;
                    }

                    @Override
                    public void endTraverse( final RelationshipGraph graph )
                        throws RelationshipGraphConnectionException
                    {
                    }

                    @Override
                    public void edgeTraversed( final ProjectRelationship<?> relationship,
                                               final List<ProjectRelationship<?>> path )
                    {
                    }
                } );
            }
            catch ( final RelationshipGraphException e )
            {
                e.printStackTrace();
            }
        }

    }

}