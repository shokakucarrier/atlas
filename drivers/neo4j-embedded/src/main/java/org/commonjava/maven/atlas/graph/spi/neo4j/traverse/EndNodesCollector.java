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

import static org.apache.commons.lang.StringUtils.join;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

import org.commonjava.maven.atlas.graph.model.GraphView;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpander;

@SuppressWarnings( "rawtypes" )
public class EndNodesCollector
    extends AbstractAtlasCollector<Node>
{

    private final Set<Node> endNodes;

    public EndNodesCollector( final Node start, final Node end, final GraphView view, final Node wsNode, final boolean checkExistence )
    {
        this( Collections.singleton( start ), Collections.singleton( end ), view, wsNode, checkExistence );
    }

    public EndNodesCollector( final Set<Node> startNodes, final Set<Node> endNodes, final GraphView view, final Node wsNode,
                              final boolean checkExistence )
    {
        super( startNodes, view, wsNode, checkExistence );
        this.endNodes = endNodes;
        logger.debug( "Collector: start=(%s), end=(%s), view=(%s), checkExistence=%s", join( startNodes, ", " ), join( endNodes, ", " ), view,
                      checkExistence );
        //        this.logEnabled = true;
    }

    private EndNodesCollector( final Set<Node> startNodes, final Set<Node> endNodes, final GraphView view, final Node wsNode,
                               final boolean checkExistence, final Direction direction )
    {
        super( startNodes, view, wsNode, checkExistence, direction );
        this.endNodes = endNodes;
        //        this.logEnabled = true;
    }

    @Override
    public PathExpander reverse()
    {
        return new EndNodesCollector( startNodes, endNodes, view, wsNode, checkExistence, direction.reverse() );
    }

    public boolean hasFoundNodes()
    {
        return !found.isEmpty();
    }

    public Set<Node> getFoundNodes()
    {
        return found;
    }

    @Override
    public Iterator<Node> iterator()
    {
        return found.iterator();
    }

    @Override
    protected boolean returnChildren( final Path path )
    {
        final Node end = path.endNode();
        if ( endNodes.contains( end ) )
        {
            if ( accept( path ) )
            {
                //                logger.info( "FOUND path ending in: %s", endId );
                found.add( end );
            }

            return false;
        }

        return true;
    }

}
