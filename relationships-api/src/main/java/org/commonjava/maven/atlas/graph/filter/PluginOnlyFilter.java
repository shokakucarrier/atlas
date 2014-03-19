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
package org.commonjava.maven.atlas.graph.filter;

import org.commonjava.maven.atlas.graph.rel.PluginRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.RelationshipType;

public class PluginOnlyFilter
    extends AbstractTypedFilter
{

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    public PluginOnlyFilter()
    {
        this( false, true );
    }

    public PluginOnlyFilter( final boolean includeManaged, final boolean includeConcrete )
    {
        super( RelationshipType.PLUGIN, false, includeManaged, includeConcrete );
    }

    @Override
    public boolean doAccept( final ProjectRelationship<?> rel )
    {
        final PluginRelationship pr = (PluginRelationship) rel;
        if ( isManagedInfoIncluded() && pr.isManaged() )
        {
            return true;
        }
        else if ( isConcreteInfoIncluded() && !pr.isManaged() )
        {
            return true;
        }

        return false;
    }

    @Override
    public ProjectRelationshipFilter getChildFilter( final ProjectRelationship<?> parent )
    {
        return NoneFilter.INSTANCE;
    }

}
