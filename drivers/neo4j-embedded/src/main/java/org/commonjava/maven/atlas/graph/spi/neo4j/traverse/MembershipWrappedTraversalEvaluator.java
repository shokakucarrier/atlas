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
package org.commonjava.maven.atlas.graph.spi.neo4j.traverse;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.commonjava.maven.atlas.graph.model.GraphView;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.spi.neo4j.AbstractNeo4JEGraphDriver;
import org.commonjava.maven.atlas.graph.spi.neo4j.io.Conversions;
import org.commonjava.maven.atlas.graph.traverse.ProjectNetTraversal;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpander;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.traversal.BranchState;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MembershipWrappedTraversalEvaluator<STATE>
    implements Evaluator, PathExpander<STATE>
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Set<Long> rootIds;

    private final ProjectNetTraversal traversal;

    private final Map<PathKey, GraphPathInfo> pathInfos = new HashMap<PathKey, GraphPathInfo>();

    private final Set<Long> seenRels = new HashSet<Long>();

    private final Set<Long> accepted = new HashSet<Long>();

    private final Set<Long> rejected = new HashSet<Long>();

    private final int pass;

    private boolean reversedExpander;

    private int expHits = 0;

    private int evalHits = 0;

    private int expMemberMisses = 0;

    private int evalMemberMisses = 0;

    private int expMemberHits = 0;

    private int evalMemberHits = 0;

    private int evalDupes = 0;

    private int expPreChecks = 0;

    private int evalPreChecks = 0;

    private final GraphView view;

    public MembershipWrappedTraversalEvaluator( final Set<Long> rootIds, final ProjectNetTraversal traversal, final GraphView view, final int pass )
    {
        this.rootIds = rootIds;
        this.traversal = traversal;
        this.view = view;
        this.pass = pass;
    }

    private MembershipWrappedTraversalEvaluator( final MembershipWrappedTraversalEvaluator<STATE> ev, final boolean reversedExpander )
    {
        this.rootIds = ev.rootIds;
        this.traversal = ev.traversal;
        this.pass = ev.pass;
        this.view = ev.view;
        this.reversedExpander = reversedExpander;
    }

    public void printStats()
    {
        logger.info( "\n\n\n\nStats for traversal:\n" + "---------------------\n" + "\ntotal expander hits: {}" + "\nexpander membership hits: {}"
                         + "\nexpander membership misses: {}" + "\nexpander preCheck() calls: {}" + "\n\ntotal evaluator hits: {}"
                         + "\nevaluator membership hits: {}" + "\nevaluator membership misses: {}" + "\nevaluator duplicate hits: {}"
                         + "\nevaluator preCheck() calls: {}\n\n\n\n", expHits, expMemberHits, expMemberMisses, expPreChecks, evalHits,
                     evalMemberHits,
                     evalMemberMisses, evalDupes, evalPreChecks );
    }

    @Override
    public Evaluation evaluate( final Path path )
    {
        evalHits++;

        final Relationship rel = path.lastRelationship();
        if ( rel == null )
        {
            //            logger.info( "MISSING last-relationship: {}. exclude and continue", rel );
            return Evaluation.EXCLUDE_AND_CONTINUE;
        }

        if ( seenRels.contains( rel.getId() ) )
        {
            //            logger.info( "SEEN last-relationship: {}. exclude and prune", rel );
            evalDupes++;
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        seenRels.add( rel.getId() );

        if ( accepted.contains( rel.getId() ) )
        {
            //            logger.info( "ACCEPTED last-relationship: {}. include and continue", rel );
            evalMemberHits++;
            evalPreChecks++;
            return Evaluation.INCLUDE_AND_CONTINUE;
        }
        else if ( rejected.contains( rel.getId() ) )
        {
            //            logger.info( "REJECTED last-relationship: {}. exclude and prune", rel );
            evalMemberHits++;
            return Evaluation.EXCLUDE_AND_PRUNE;
        }

        final Set<Long> roots = rootIds;
        if ( roots == null || roots.isEmpty() || roots.contains( path.startNode()
                                                                     .getId() ) )
        {
            evalMemberHits++;

            final ProjectRelationship<?> lastRel = Conversions.toProjectRelationship( rel );

            final List<ProjectRelationship<?>> relPath = Conversions.convertToRelationships( path.relationships() );
            if ( relPath.indexOf( lastRel ) == relPath.size() - 1 )
            {
                //                logger.warn( "\n\n\n\n\nREMOVING last-relationship: {} from path!\n\n\n\n\n" );
                relPath.remove( relPath.size() - 1 );
            }

            if ( relPath.isEmpty() )
            {
                return Evaluation.EXCLUDE_AND_CONTINUE;
            }
            else
            {
                if ( traversal.preCheck( lastRel, relPath, pass ) )
                {
                    //                    logger.info( "PRE-CHECK+ last-relationship: {}. include and continue", rel );
                    accepted.add( rel.getId() );
                    evalPreChecks++;

                    return Evaluation.INCLUDE_AND_CONTINUE;
                }
                else
                {
                    //                    logger.info( "PRE-CHECK- last-relationship: {}.", rel );
                    rejected.add( rel.getId() );
                }
            }
        }
        else
        {
            //            logger.info( "NON-ROOTED-PATH: {}.", path );
            evalMemberMisses++;
        }

        //        logger.info( "exclude and prune" );
        return Evaluation.EXCLUDE_AND_PRUNE;
    }

    @Override
    public Iterable<Relationship> expand( final Path path, final BranchState<STATE> state )
    {
        expHits++;

        final Node node = path.endNode();
        //        logger.info( "START expansion for: {}", path );

        // TODO: Is node(0) appropriate to see??
        final Set<Long> roots = rootIds;
        if ( node.getId() != 0 && roots != null && roots.isEmpty() && !roots.contains( path.startNode()
                                                                                           .getId() ) )
        {
            expMemberMisses++;
            //            logger.info( "{} not in membership. Skipping expansion.", node );
            return Collections.emptySet();
        }

        final PathKey key = new PathKey( path );
        GraphPathInfo pathInfo = pathInfos.remove( key );

        if ( pathInfo == null )
        {
            pathInfo = new GraphPathInfo( view );
        }

        //        final Relationship rel = path.lastRelationship();
        //        if ( rel != null )
        //        {
        //            final AbstractNeo4JEGraphDriver db = (AbstractNeo4JEGraphDriver) view.getDatabase();
        //            final Relationship sel = db == null ? null : db.select( rel, view );
        //
        //            if ( ( sel == null || sel == rel ) && Conversions.getBooleanProperty( Conversions.SELECTION, rel, false ) )
        //            {
        //                expMemberMisses++;
        //                return Collections.emptySet();
        //            }
        //        }
        //
        expMemberHits++;

        final Iterable<Relationship> rs = node.getRelationships( reversedExpander ? Direction.INCOMING : Direction.OUTGOING );
        if ( rs == null )
        {
            //            logger.info( "No relationships from end-node: {}", node );
            return Collections.emptySet();
        }

        final Set<Relationship> result = new HashSet<Relationship>();
        List<ProjectRelationship<?>> rels = null;
        for ( Relationship r : rs )
        {
            final AbstractNeo4JEGraphDriver db = (AbstractNeo4JEGraphDriver) view.getDatabase();
            final Relationship selected = db == null ? null : db.select( r, view, pathInfo );

            // if no selection happened and r is a selection-only relationship, skip it.
            if ( ( selected == null || selected == r ) && Conversions.getBooleanProperty( Conversions.SELECTION, r, false ) )
            {
                continue;
            }

            if ( selected != null )
            {
                r = selected;
            }
            //            logger.info( "Attempting to expand: {}", r );

            final ProjectRelationship<?> projectRel = Conversions.toProjectRelationship( r );
            final GraphPathInfo next = pathInfo.getChildPathInfo( projectRel );
            if ( accepted.contains( r.getId() ) )
            {
                //                logger.info( "Previously accepted for expansion: {}", r.getId() );
                pathInfos.put( new PathKey( path, r ), next );
                result.add( r );
                continue;
            }
            else if ( rejected.contains( r.getId() ) )
            {
                //                logger.warn( "Previously rejected for expansion: {}", r.getId() );
                continue;
            }

            //            logger.info( "Pre-checking relationship {} for expansion using filter: {}", r, traversal );
            if ( rels == null )
            {
                rels = getPathRelationships( path );
            }

            if ( traversal.preCheck( projectRel, rels, pass ) )
            {
                accepted.add( r.getId() );
                expPreChecks++;
                //                logger.info( "Adding for expansion: {}", r );
                pathInfos.put( new PathKey( path, r ), next );
                result.add( r );
            }
            else
            {
                rejected.add( r.getId() );
            }
        }

        //        logger.info( "Expanding for {} relationships.", result.size() );
        return result;
    }

    private List<ProjectRelationship<?>> getPathRelationships( final Path path )
    {
        List<ProjectRelationship<?>> rels;
        final Iterable<Relationship> rs = path.relationships();
        if ( rs == null )
        {
            //            logger.info( "Constructing empty relationship list for filter." );
            rels = Collections.emptyList();
        }
        else
        {
            rels = Conversions.convertToRelationships( rs );
            //            logger.info( "Got relationship list {} entries long for filter", rels.size() );
        }

        if ( reversedExpander )
        {
            //            logger.info( "Reversing relationship list for filter." );
            Collections.reverse( rels );
        }

        return rels;
    }

    @Override
    public PathExpander<STATE> reverse()
    {
        return new MembershipWrappedTraversalEvaluator<STATE>( this, true );
    }

}
