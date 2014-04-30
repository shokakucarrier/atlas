/*******************************************************************************
 * Copyright (c) 2014 Red Hat, Inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.commonjava.maven.atlas.graph.traverse;

import java.util.List;

import org.commonjava.maven.atlas.graph.filter.AnyFilter;
import org.commonjava.maven.atlas.graph.filter.NoneFilter;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;

public abstract class AbstractFilteringTraversal
    extends AbstractTraversal
{

    private final ProjectRelationshipFilter rootFilter;

    protected AbstractFilteringTraversal()
    {
        rootFilter = AnyFilter.INSTANCE;
    }

    protected AbstractFilteringTraversal( final ProjectRelationshipFilter filter )
    {
        rootFilter = filter;
    }

    protected abstract boolean shouldTraverseEdge( ProjectRelationship<?> relationship,
                                                   List<ProjectRelationship<?>> path );

    protected void edgeTraversalFinished( final ProjectRelationship<?> relationship,
                                          final List<ProjectRelationship<?>> path )
    {
    }

    public final ProjectRelationshipFilter getRootFilter()
    {
        return rootFilter;
    }

    @Override
    public final void edgeTraversed( final ProjectRelationship<?> relationship, final List<ProjectRelationship<?>> path )
    {
        edgeTraversalFinished( relationship, path );
    }

    @Override
    public final boolean traverseEdge( final ProjectRelationship<?> relationship,
                                       final List<ProjectRelationship<?>> path )
    {
        if ( !preCheck( relationship, path ) )
        {
            return false;
        }

        //        seen.add( relationship );

        final boolean ok = shouldTraverseEdge( relationship, path );

        return ok;
    }

    @Override
    public boolean preCheck( final ProjectRelationship<?> relationship, final List<ProjectRelationship<?>> path )
    {
        boolean result = true;

        final ProjectRelationshipFilter filter = constructFilter( path );
        if ( result && filter != null && !filter.accept( relationship ) )
        {
            result = false;
        }

        return result;
    }

    private ProjectRelationshipFilter constructFilter( final List<ProjectRelationship<?>> path )
    {
        if ( rootFilter == null )
        {
            return null;
        }

        ProjectRelationshipFilter filter = rootFilter;
        for ( final ProjectRelationship<?> rel : path )
        {
            if ( !filter.accept( rel ) )
            {
                return NoneFilter.INSTANCE;
            }
            else
            {
                filter = filter.getChildFilter( rel );
            }
        }

        return filter;
    }

}
