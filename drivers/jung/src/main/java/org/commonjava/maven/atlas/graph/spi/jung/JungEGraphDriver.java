/*******************************************************************************
 * Copyright (C) 2014 John Casey.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.commonjava.maven.atlas.graph.spi.jung;

import static org.commonjava.maven.atlas.graph.util.RelationshipUtils.POM_ROOT_URI;
import static org.commonjava.maven.atlas.graph.util.RelationshipUtils.UNKNOWN_SOURCE_URI;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.model.EProjectCycle;
import org.commonjava.maven.atlas.graph.model.EProjectNet;
import org.commonjava.maven.atlas.graph.model.GraphView;
import org.commonjava.maven.atlas.graph.mutate.VersionManager;
import org.commonjava.maven.atlas.graph.rel.ParentRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.RelationshipComparator;
import org.commonjava.maven.atlas.graph.rel.RelationshipType;
import org.commonjava.maven.atlas.graph.spi.GraphDatabaseDriver;
import org.commonjava.maven.atlas.graph.spi.GraphDriverException;
import org.commonjava.maven.atlas.graph.spi.jung.model.JungGraphPath;
import org.commonjava.maven.atlas.graph.spi.model.GraphPath;
import org.commonjava.maven.atlas.graph.traverse.ProjectNetTraversal;
import org.commonjava.maven.atlas.graph.util.RelationshipUtils;
import org.commonjava.maven.atlas.graph.workspace.GraphWorkspace;
import org.commonjava.maven.atlas.graph.workspace.GraphWorkspaceConfiguration;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.util.JoinString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.uci.ics.jung.graph.DirectedGraph;
import edu.uci.ics.jung.graph.DirectedSparseMultigraph;

public class JungEGraphDriver
    implements GraphDatabaseDriver
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private DirectedGraph<ProjectVersionRef, ProjectRelationship<?>> graph =
        new DirectedSparseMultigraph<ProjectVersionRef, ProjectRelationship<?>>();

    private final Map<ProjectRef, Set<ProjectVersionRef>> byGA = new HashMap<ProjectRef, Set<ProjectVersionRef>>();

    private transient Set<ProjectVersionRef> incompleteSubgraphs = new HashSet<ProjectVersionRef>();

    private transient Set<ProjectVersionRef> variableSubgraphs = new HashSet<ProjectVersionRef>();

    private final Map<String, Set<ProjectVersionRef>> metadataOwners = new HashMap<String, Set<ProjectVersionRef>>();

    private final Map<ProjectVersionRef, Map<String, String>> metadata = new HashMap<ProjectVersionRef, Map<String, String>>();

    private final Set<EProjectCycle> cycles = new HashSet<EProjectCycle>();

    @Override
    public Collection<? extends ProjectRelationship<?>> getRelationshipsDeclaredBy( final GraphView view, final ProjectVersionRef ref )
    {
        return imposeSelections( view, graph.getOutEdges( ref.asProjectVersionRef() ) );
    }

    @Override
    public Collection<? extends ProjectRelationship<?>> getRelationshipsTargeting( final GraphView view, final ProjectVersionRef ref )
    {
        return imposeSelections( view, graph.getInEdges( ref.asProjectVersionRef() ) );
    }

    @Override
    public Collection<ProjectRelationship<?>> getAllRelationships( final GraphView view )
    {
        return imposeSelections( view, graph.getEdges() );
    }

    private Collection<ProjectRelationship<?>> imposeSelections( final GraphView view, final Collection<ProjectRelationship<?>> edges )
    {
        if ( edges == null || edges.isEmpty() )
        {
            return edges;
        }

        final GraphWorkspace workspace = view.getWorkspace();
        if ( workspace == null )
        {
            // no selections here...
            return edges;
        }

        final List<ProjectRelationship<?>> result = new ArrayList<ProjectRelationship<?>>( edges.size() );
        for ( final ProjectRelationship<?> edge : edges )
        {
            final ProjectVersionRef target = edge.getTarget()
                                                 .asProjectVersionRef();
            final Set<URI> sources = workspace.getActiveSources();
            if ( sources != null && !sources.isEmpty() )
            {
                Set<URI> s = edge.getSources();
                if ( s == null )
                {
                    s = Collections.singleton( UNKNOWN_SOURCE_URI );
                }

                boolean found = false;
                for ( final URI uri : s )
                {
                    if ( sources == GraphWorkspaceConfiguration.DEFAULT_SOURCES || sources.contains( uri ) )
                    {
                        found = true;
                        break;
                    }
                }

                if ( !found )
                {
                    //                    log( "Found relationship in path with de-selected source-repository URI: %s", edge );
                    continue;
                }
            }

            final Set<URI> pomLocations = workspace.getActivePomLocations();
            if ( pomLocations != null && !pomLocations.isEmpty() )
            {
                URI pomLocation = edge.getPomLocation();
                if ( pomLocation == null )
                {
                    pomLocation = POM_ROOT_URI;
                }
                if ( !pomLocations.contains( pomLocation ) )
                {
                    //                    log( "Found relationship in path with de-selected pom-location URI: %s", edge );
                    continue;
                }
            }

            final VersionManager selections = view.getSelections();
            ProjectVersionRef selected = null;
            if ( selections != null )
            {
                selected = selections.getSelected( target );

                if ( selected == null )
                {
                    selected = selections.getSelected( target.asProjectRef() );
                }
            }

            if ( selected != null )
            {
                result.add( edge.selectTarget( selected ) );
            }
            else
            {
                result.add( edge );
            }
        }

        return result;
    }

    @Override
    public Set<ProjectRelationship<?>> addRelationships( final ProjectRelationship<?>... rels )
    {
        final Set<ProjectRelationship<?>> skipped = new HashSet<ProjectRelationship<?>>();
        for ( final ProjectRelationship<?> rel : rels )
        {
            if ( !graph.containsVertex( rel.getDeclaring() ) )
            {
                //                // logger.info( "Adding node: %s", rel.getDeclaring() );
                graph.addVertex( rel.getDeclaring() );
                addGA( rel.getDeclaring() );
            }

            final ProjectVersionRef target = rel.getTarget()
                                                .asProjectVersionRef();
            if ( !target.getVersionSpec()
                        .isSingle() )
            {
                // logger.info( "Adding variable target: %s", target );
                variableSubgraphs.add( target );
            }
            else if ( !graph.containsVertex( target ) )
            {
                // logger.info( "Adding incomplete target: %s", target );
                incompleteSubgraphs.add( target );
            }

            if ( !graph.containsVertex( target ) )
            {
                // logger.info( "Adding node: %s", target );
                graph.addVertex( target.asProjectVersionRef() );
                addGA( target );
            }

            final List<ProjectRelationship<?>> edges = new ArrayList<ProjectRelationship<?>>( graph.findEdgeSet( rel.getDeclaring(), target ) );
            if ( !edges.contains( rel ) )
            {
                // logger.info( "Adding edge: %s -> %s", rel.getDeclaring(), target );
                graph.addEdge( rel, rel.getDeclaring(), target.asProjectVersionRef() );
            }
            else
            {
                final int idx = edges.indexOf( rel );
                final ProjectRelationship<?> existing = edges.get( idx );

                // logger.info( "Adding sources: %s to existing edge: %s", rel.getSources(), existing );

                existing.addSources( rel.getSources() );
            }

            // logger.info( "removing from incomplete status: %s", rel.getDeclaring() );
            incompleteSubgraphs.remove( rel.getDeclaring() );
        }

        for ( final ProjectRelationship<?> rel : rels )
        {
            if ( skipped.contains( rel ) )
            {
                continue;
            }

            // logger.info( "Detecting cycles introduced by: %s", rel );

            final CycleDetectionTraversal traversal = new CycleDetectionTraversal( rel );

            dfsTraverse( new GraphView( new GraphWorkspace( "GLOBAL", new GraphWorkspaceConfiguration(), this ) ), traversal, 0,
                         rel.getTarget()
                            .asProjectVersionRef() );

            final List<EProjectCycle> cycles = traversal.getCycles();

            if ( !cycles.isEmpty() )
            {
                // logger.info( "CYCLE introduced by: %s", rel );
                skipped.add( rel );

                graph.removeEdge( rel );
                this.cycles.addAll( cycles );
            }
        }

        return skipped;
    }

    private boolean addGA( final ProjectVersionRef ref )
    {
        final ProjectRef pr = ref.asProjectRef();
        Set<ProjectVersionRef> refs = byGA.get( pr );
        if ( refs == null )
        {
            refs = new HashSet<ProjectVersionRef>();
            byGA.put( pr, refs );
        }

        return refs.add( ref );
    }

    @Override
    public Set<List<ProjectRelationship<?>>> getAllPathsTo( final GraphView view, final ProjectVersionRef... refs )
    {
        final PathDetectionTraversal traversal = new PathDetectionTraversal( refs );

        final Set<ProjectVersionRef> roots = view.getRoots();
        if ( roots == null )
        {
            LoggerFactory.getLogger( getClass() )
                         .warn( "Cannot retrieve paths targeting {}. No roots specified for this project network!", new JoinString( ", ", refs ) );
            return null;
        }

        for ( final ProjectVersionRef root : roots )
        {
            dfsTraverse( view, traversal, 0, root );
        }

        return traversal.getPaths();
    }

    @Override
    public boolean introducesCycle( final GraphView view, final ProjectRelationship<?> rel )
    {
        final CycleDetectionTraversal traversal = new CycleDetectionTraversal( rel );

        dfsTraverse( view, traversal, 0, rel.getTarget()
                                            .asProjectVersionRef() );

        return !traversal.getCycles()
                         .isEmpty();
    }

    @Override
    public Set<ProjectVersionRef> getAllProjects( final GraphView view )
    {
        return new HashSet<ProjectVersionRef>( graph.getVertices() );
    }

    @Override
    public void traverse( final GraphView view, final ProjectNetTraversal traversal, final EProjectNet net, final ProjectVersionRef root )
        throws GraphDriverException
    {
        final int passes = traversal.getRequiredPasses();
        for ( int i = 0; i < passes; i++ )
        {
            traversal.startTraverse( i, net );

            switch ( traversal.getType( i ) )
            {
                case breadth_first:
                {
                    bfsTraverse( view, traversal, i, root );
                    break;
                }
                case depth_first:
                {
                    dfsTraverse( view, traversal, i, root );
                    break;
                }
            }

            traversal.endTraverse( i, net );
        }
    }

    // TODO: Implement without recursion.
    private void dfsTraverse( final GraphView view, final ProjectNetTraversal traversal, final int pass, final ProjectVersionRef root )
    {
        dfsIterate( view, root, traversal, new GraphPathInfo( root, view, new JungGraphPath( root ) ), pass );
    }

    private void dfsIterate( final GraphView view, final ProjectVersionRef node, final ProjectNetTraversal traversal, final GraphPathInfo path,
                             final int pass )
    {
        final List<ProjectRelationship<?>> edges = getSortedOutEdges( view, node );
        if ( edges != null )
        {
            for ( final ProjectRelationship<?> edge : edges )
            {
                final GraphPathInfo next = path.getChildPath( edge );
                if ( next == null )
                {
                    continue;
                }

                if ( traversal.traverseEdge( next.getTargetEdge(), next.getPathElements(), pass ) )
                {
                    if ( !( edge instanceof ParentRelationship ) || !( (ParentRelationship) edge ).isTerminus() )
                    {
                        if ( next.hasCycle() )
                        {
                            continue;
                        }

                        final ProjectVersionRef target = edge.getTarget()
                                                             .asProjectVersionRef();

                        dfsIterate( view, target, traversal, next, pass );
                    }

                    traversal.edgeTraversed( next.getTargetEdge(), next.getPathElements(), pass );
                }
            }
        }
    }

    // TODO: Implement without recursion.
    private void bfsTraverse( final GraphView view, final ProjectNetTraversal traversal, final int pass, final ProjectVersionRef root )
    {
        final GraphPathInfo path = new GraphPathInfo( root, view, new JungGraphPath( root ) );

        bfsIterate( view, Collections.singletonList( path ), traversal, pass );
    }

    private void bfsIterate( final GraphView view, final List<GraphPathInfo> thisLayer, final ProjectNetTraversal traversal, final int pass )
    {
        final List<GraphPathInfo> nextLayer = new ArrayList<GraphPathInfo>();

        for ( final GraphPathInfo path : thisLayer )
        {
            if ( path.isEmpty() )
            {
                continue;
            }

            final ProjectVersionRef node = path.getTarget();

            final List<ProjectRelationship<?>> edges = getSortedOutEdges( view, node );
            if ( edges != null )
            {
                for ( final ProjectRelationship<?> edge : edges )
                {
                    final GraphPathInfo next = path.getChildPath( edge );
                    if ( next == null )
                    {
                        continue;
                    }

                    // call traverseEdge no matter what, to allow traversal to "see" all relationships.
                    if ( traversal.traverseEdge( next.getTargetEdge(), next.getPathElements(), pass ) )
                    {
                        // Don't account for terminal parent relationship.
                        if ( !( edge instanceof ParentRelationship ) || !( (ParentRelationship) edge ).isTerminus() )
                        {
                            if ( next.hasCycle() )
                            {
                                continue;
                            }

                            nextLayer.add( next );
                        }

                        traversal.edgeTraversed( next.getTargetEdge(), next.getPathElements(), pass );
                    }
                }
            }
        }

        if ( !nextLayer.isEmpty() )
        {
            Collections.sort( nextLayer );
            bfsIterate( view, nextLayer, traversal, pass );
        }
    }

    private List<ProjectRelationship<?>> getSortedOutEdges( final GraphView view, final ProjectVersionRef node )
    {
        Collection<ProjectRelationship<?>> unsorted = graph.getOutEdges( node.asProjectVersionRef() );
        if ( unsorted == null )
        {
            return null;
        }

        unsorted = new ArrayList<ProjectRelationship<?>>( unsorted );

        RelationshipUtils.filterTerminalParents( unsorted );

        final List<ProjectRelationship<?>> sorted = new ArrayList<ProjectRelationship<?>>( imposeSelections( view, unsorted ) );
        Collections.sort( sorted, RelationshipComparator.INSTANCE );

        return sorted;
    }

    //    @Override
    //    public EGraphDriver newInstanceFrom( final EProjectNet net, final ProjectRelationshipFilter filter,
    //                                         final ProjectVersionRef... from )
    //        throws GraphDriverException
    //    {
    //        final JungEGraphDriver neo = new JungEGraphDriver( this, filter, net, null, from );
    //        neo.restrictProjectMembership( Arrays.asList( from ) );
    //
    //        return neo;
    //    }
    //
    //    @Override
    //    public EGraphDriver newInstance( final EGraphSession workspace, final EProjectNet net,
    //                                     final ProjectRelationshipFilter filter, final ProjectVersionRef... from )
    //        throws GraphDriverException
    //    {
    //        final JungEGraphDriver neo = new JungEGraphDriver( this, filter, net, null, from );
    //        neo.restrictProjectMembership( Arrays.asList( from ) );
    //
    //        return neo;
    //    }

    @Override
    public boolean containsProject( final GraphView view, final ProjectVersionRef ref )
    {
        return graph.containsVertex( ref.asProjectVersionRef() ) && !incompleteSubgraphs.contains( ref.asProjectVersionRef() );
    }

    @Override
    public boolean containsRelationship( final GraphView view, final ProjectRelationship<?> rel )
    {
        return graph.containsEdge( rel );
    }

    public void restrictProjectMembership( final Collection<ProjectVersionRef> refs )
    {
        final Set<ProjectRelationship<?>> rels = new HashSet<ProjectRelationship<?>>();
        for ( final ProjectVersionRef ref : refs )
        {
            final Collection<ProjectRelationship<?>> edges = graph.getOutEdges( ref.asProjectVersionRef() );
            if ( edges != null )
            {
                rels.addAll( edges );
            }
        }

        restrictRelationshipMembership( rels );
    }

    public void restrictRelationshipMembership( final Collection<ProjectRelationship<?>> rels )
    {
        graph = new DirectedSparseMultigraph<ProjectVersionRef, ProjectRelationship<?>>();
        incompleteSubgraphs.clear();
        variableSubgraphs.clear();

        addRelationships( rels.toArray( new ProjectRelationship<?>[] {} ) );

        recomputeIncompleteSubgraphs();
    }

    @Override
    public void close()
        throws IOException
    {
        // NOP; stored in memory.
    }

    //    @Override
    //    public boolean isDerivedFrom( final EGraphDriver driver )
    //    {
    //        return false;
    //    }

    @Override
    public boolean isMissing( final GraphView view, final ProjectVersionRef project )
    {
        return !graph.containsVertex( project.asProjectVersionRef() );
    }

    @Override
    public boolean hasMissingProjects( final GraphView view )
    {
        return !incompleteSubgraphs.isEmpty();
    }

    @Override
    public Set<ProjectVersionRef> getMissingProjects( final GraphView view )
    {
        final Set<ProjectVersionRef> result = new HashSet<ProjectVersionRef>( incompleteSubgraphs );
        // logger.info( "Got %d missing projects: %s", result.size(), result );
        return result;
    }

    @Override
    public boolean hasVariableProjects( final GraphView view )
    {
        return !variableSubgraphs.isEmpty();
    }

    @Override
    public Set<ProjectVersionRef> getVariableProjects( final GraphView view )
    {
        return new HashSet<ProjectVersionRef>( variableSubgraphs );
    }

    @Override
    public boolean addCycle( final EProjectCycle cycle )
    {
        boolean changed = false;
        synchronized ( this.cycles )
        {
            changed = this.cycles.add( cycle );
        }

        for ( final ProjectRelationship<?> rel : cycle )
        {
            incompleteSubgraphs.remove( rel.getDeclaring() );
        }

        return changed;
    }

    // TODO: May not work with paths to the entries in the cycle...since filters are often path-sensitive
    @Override
    public Set<EProjectCycle> getCycles( final GraphView view )
    {
        final Set<EProjectCycle> result = new HashSet<EProjectCycle>();
        if ( view.getFilter() == null )
        {
            result.addAll( cycles );
        }
        else
        {
            final ProjectRelationshipFilter filter = view.getFilter();
            nextCycle: for ( final EProjectCycle cycle : cycles )
            {
                for ( final ProjectRelationship<?> r : cycle )
                {
                    if ( !filter.accept( r ) )
                    {
                        continue nextCycle;
                    }
                }
            }
        }

        return result;
    }

    @Override
    public boolean isCycleParticipant( final GraphView view, final ProjectRelationship<?> rel )
    {
        for ( final EProjectCycle cycle : cycles )
        {
            if ( cycle.contains( rel ) )
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isCycleParticipant( final GraphView view, final ProjectVersionRef ref )
    {
        for ( final EProjectCycle cycle : cycles )
        {
            if ( cycle.contains( ref.asProjectVersionRef() ) )
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void recomputeIncompleteSubgraphs()
    {
        final GraphView view = new GraphView( new GraphWorkspace( "GLOBAL", new GraphWorkspaceConfiguration(), this ) );
        for ( final ProjectVersionRef vertex : getAllProjects( view ) )
        {
            final Collection<? extends ProjectRelationship<?>> outEdges = getRelationshipsDeclaredBy( view, vertex );
            if ( outEdges != null && !outEdges.isEmpty() )
            {
                incompleteSubgraphs.remove( vertex );
            }
        }
    }

    @Override
    public Map<String, String> getMetadata( final ProjectVersionRef ref )
    {
        return getMetadata( ref, null );
    }

    @Override
    public Map<String, String> getMetadata( final ProjectVersionRef ref, final Set<String> keys )
    {
        Map<String, String> metadata;
        synchronized ( this )
        {
            metadata = this.metadata.get( ref.asProjectVersionRef() );
            if ( metadata == null )
            {
                metadata = new HashMap<String, String>();
                this.metadata.put( ref.asProjectVersionRef(), metadata );
            }
        }

        if ( keys != null && !keys.isEmpty() )
        {
            metadata = new HashMap<String, String>( metadata );
            final Set<String> removable = new HashSet<String>( metadata.keySet() );
            removable.removeAll( keys );

            for ( final String remove : removable )
            {
                metadata.remove( remove );
            }
        }

        return metadata;
    }

    @Override
    public void addMetadata( final ProjectVersionRef ref, final String key, final String value )
    {
        if ( StringUtils.isEmpty( key ) || StringUtils.isEmpty( value ) )
        {
            return;
        }

        final Map<String, String> md = getMetadata( ref.asProjectVersionRef() );
        md.put( key, value );

        addMetadataOwner( key, ref.asProjectVersionRef() );
    }

    private synchronized void addMetadataOwner( final String key, final ProjectVersionRef ref )
    {
        Set<ProjectVersionRef> owners = this.metadataOwners.get( key );
        if ( owners == null )
        {
            owners = new HashSet<ProjectVersionRef>();
            metadataOwners.put( key, owners );
        }

        owners.add( ref.asProjectVersionRef() );
    }

    @Override
    public void setMetadata( final ProjectVersionRef ref, final Map<String, String> metadata )
    {
        if ( metadata == null || metadata.isEmpty() )
        {
            return;
        }

        final Map<String, String> md = getMetadata( ref.asProjectVersionRef() );
        md.putAll( metadata );
    }

    @Override
    public synchronized void reindex()
        throws GraphDriverException
    {
        for ( final Map.Entry<ProjectVersionRef, Map<String, String>> refEntry : metadata.entrySet() )
        {
            for ( final Map.Entry<String, String> mdEntry : refEntry.getValue()
                                                                    .entrySet() )
            {
                addMetadataOwner( mdEntry.getKey(), refEntry.getKey()
                                                            .asProjectVersionRef() );
            }
        }
    }

    @Override
    public Set<ProjectVersionRef> getProjectsWithMetadata( final GraphView view, final String key )
    {
        return metadataOwners.get( key );
    }

    //    public void selectVersionFor( final ProjectVersionRef variable, final ProjectVersionRef select )
    //        throws GraphDriverException
    //    {
    //        if ( !select.isSpecificVersion() )
    //        {
    //            throw new GraphDriverException( "Cannot select non-concrete version! Attempted to select: %s", select );
    //        }
    //
    //        if ( variable.isSpecificVersion() )
    //        {
    //            throw new GraphDriverException(
    //                                            "Cannot select version if target is already a concrete version! Attempted to select for: %s",
    //                                            variable );
    //        }
    //
    //        selected.put( variable, select );
    //
    //        // Don't worry about selecting for outbound edges, as those subgraphs are supposed to be the same...
    //        final Collection<ProjectRelationship<?>> rels = graph.getInEdges( variable );
    //        for ( final ProjectRelationship<?> rel : rels )
    //        {
    //
    //            ProjectRelationship<?> repl;
    //            if ( rel.getTarget()
    //                    .asProjectVersionRef()
    //                    .equals( variable ) )
    //            {
    //                repl = rel.selectTarget( (SingleVersion) select.getVersionSpec() );
    //            }
    //            else
    //            {
    //                continue;
    //            }
    //
    //            graph.removeEdge( rel );
    //            graph.addEdge( repl, repl.getDeclaring(), repl.getTarget()
    //                                                          .asProjectVersionRef() );
    //
    //            replaced.put( rel, repl );
    //        }
    //    }
    //
    //    public Map<ProjectVersionRef, ProjectVersionRef> clearSelectedVersions()
    //    {
    //        final Map<ProjectVersionRef, ProjectVersionRef> selected =
    //            new HashMap<ProjectVersionRef, ProjectVersionRef>( this.selected );
    //
    //        selected.clear();
    //
    //        for ( final Map.Entry<ProjectRelationship<?>, ProjectRelationship<?>> entry : replaced.entrySet() )
    //        {
    //            final ProjectRelationship<?> rel = entry.getKey();
    //            final ProjectRelationship<?> repl = entry.getValue();
    //
    //            graph.removeEdge( repl );
    //            graph.addEdge( rel, rel.getDeclaring(), rel.getTarget()
    //                                                       .asProjectVersionRef() );
    //        }
    //
    //        for ( final ProjectVersionRef select : new HashSet<ProjectVersionRef>( selected.values() ) )
    //        {
    //            final Collection<ProjectRelationship<?>> edges = graph.getInEdges( select );
    //            if ( edges.isEmpty() )
    //            {
    //                graph.removeVertex( select );
    //            }
    //        }
    //
    //        return selected;
    //    }
    //
    //    public Map<ProjectVersionRef, ProjectVersionRef> getSelectedVersions()
    //    {
    //        return selected;
    //    }

    @Override
    public void addDisconnectedProject( final ProjectVersionRef ref )
    {
        if ( !graph.containsVertex( ref.asProjectVersionRef() ) )
        {
            graph.addVertex( ref.asProjectVersionRef() );
        }
    }

    /**
     * @deprecated Use {@link #getDirectRelationshipsFrom(GraphView,ProjectVersionRef,boolean,boolean,RelationshipType...)} instead
     */
    @Deprecated
    @Override
    public Set<ProjectRelationship<?>> getDirectRelationshipsFrom( final GraphView view, final ProjectVersionRef from,
                                                                   final boolean includeManagedInfo, final RelationshipType... types )
    {
        return getDirectRelationshipsFrom( view, from, includeManagedInfo, true, types );
    }

    @Override
    public Set<ProjectRelationship<?>> getDirectRelationshipsFrom( final GraphView view, final ProjectVersionRef from,
                                                                   final boolean includeManagedInfo, final boolean includeConcreteInfo,
                                                                   final RelationshipType... types )
    {
        return getMatchingRelationships( graph.getOutEdges( from.asProjectVersionRef() ), view, includeManagedInfo, includeConcreteInfo, types );
    }

    private Set<ProjectRelationship<?>> getMatchingRelationships( final Collection<ProjectRelationship<?>> edges, final GraphView view,
                                                                  final boolean includeManagedInfo, final boolean includeConcreteInfo,
                                                                  final RelationshipType... types )
    {
        if ( edges == null )
        {
            // logger.info( "No edges found. Nothing to filter!" );
            return null;
        }

        // logger.info( "Filtering %d edges...", edges.size() );
        final Set<ProjectRelationship<?>> rels = new HashSet<ProjectRelationship<?>>( edges.size() );

        final List<RelationshipType> typeList = Arrays.asList( types );
        Collections.sort( typeList );

        for ( final ProjectRelationship<?> rel : edges )
        {
            if ( !typeList.isEmpty() && !typeList.contains( rel.getType() ) )
            {
                // logger.info( "-= %s (wrong type)", rel );
                continue;
            }

            if ( view.getFilter() != null && !view.getFilter()
                                                  .accept( rel ) )
            {
                // logger.info( "-= %s (rejected by filter)", rel );
                continue;
            }

            if ( !includeManagedInfo && rel.isManaged() )
            {
                // logger.info( "-= %s (wrong managed status)", rel );
                continue;
            }

            if ( !includeConcreteInfo && !rel.isManaged() )
            {
                // logger.info( "-= %s (wrong managed status)", rel );
                continue;
            }

            // logger.info( "+= %s", rel );
            rels.add( rel );
        }

        return rels;
    }

    /**
     * @deprecated Use {@link #getDirectRelationshipsTo(GraphView,ProjectVersionRef,boolean,boolean,RelationshipType...)} instead
     */
    @Deprecated
    @Override
    public Set<ProjectRelationship<?>> getDirectRelationshipsTo( final GraphView view, final ProjectVersionRef to, final boolean includeManagedInfo,
                                                                 final RelationshipType... types )
    {
        return getDirectRelationshipsTo( view, to, includeManagedInfo, true, types );
    }

    @Override
    public Set<ProjectRelationship<?>> getDirectRelationshipsTo( final GraphView view, final ProjectVersionRef to, final boolean includeManagedInfo,
                                                                 final boolean includeConcreteInfo, final RelationshipType... types )
    {
        // logger.info( "Getting relationships targeting: %s (types: %s)", to, join( types, ", " ) );
        return getMatchingRelationships( graph.getInEdges( to.asProjectVersionRef() ), view, includeManagedInfo, includeConcreteInfo, types );
    }

    @Override
    public Set<ProjectVersionRef> getProjectsMatching( final ProjectRef projectRef, final GraphView eProjectNetView )
    {
        return byGA.containsKey( projectRef.asProjectRef() ) ? byGA.get( projectRef.asProjectRef() ) : Collections.<ProjectVersionRef> emptySet();
    }

    @Override
    public void deleteRelationshipsDeclaredBy( final ProjectVersionRef ref )
        throws GraphDriverException
    {
        final Collection<ProjectRelationship<?>> edges = graph.getOutEdges( ref.asProjectVersionRef() );
        if ( edges != null )
        {
            for ( final ProjectRelationship<?> rel : edges )
            {
                graph.removeEdge( rel );
            }
        }

        incompleteSubgraphs.add( ref );
    }

    @Override
    public void printStats()
    {
        logger.info( "Graph contains {} nodes.", graph.getVertexCount() );
        logger.info( "Graph contains {} relationships.", graph.getEdgeCount() );
    }

    @Override
    public ProjectVersionRef getManagedTargetFor( final ProjectVersionRef target, final GraphPath<?> path, final RelationshipType type )
    {
        if ( path == null )
        {
            return null;
        }

        if ( !( path instanceof JungGraphPath ) )
        {
            throw new IllegalArgumentException( "Cannot process GraphPath's from other implementations. (Non-Jung GraphPath detected: " + path + ")" );
        }

        final ProjectRef targetGA = target.asProjectRef();

        final JungGraphPath jungpath = (JungGraphPath) path;
        for ( final ProjectVersionRef ref : jungpath )
        {
            final Collection<ProjectRelationship<?>> outEdges = graph.getOutEdges( ref );
            for ( final ProjectRelationship<?> edge : outEdges )
            {
                if ( edge.isManaged() && type == edge.getType() && targetGA.equals( edge.getTarget() ) )
                {
                    return edge.getTarget()
                               .asProjectVersionRef();
                }
            }
        }

        return null;
    }

    @Override
    public GraphPath<?> createPath( final ProjectVersionRef... nodes )
    {
        return new JungGraphPath( nodes );
    }

    @Override
    public GraphPath<?> createPath( final GraphPath<?> parent, final ProjectVersionRef child )
    {
        if ( parent != null && !( parent instanceof JungGraphPath ) )
        {
            throw new IllegalArgumentException( "Cannot get child path for: " + parent + ". This is not a JungGraphPath instance!" );
        }

        return new JungGraphPath( (JungGraphPath) parent, child );
    }
}
