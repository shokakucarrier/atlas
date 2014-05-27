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
package org.commonjava.maven.atlas.graph.spi.jung;

import org.commonjava.maven.atlas.graph.spi.RelationshipGraphConnectionFactory;
import org.commonjava.maven.atlas.tck.graph.RelationshipGraphConnection_TCK;

public class RelationshipGraphConnectionTest
    extends RelationshipGraphConnection_TCK
{
    private final JungGraphConnectionFactory connFac = new JungGraphConnectionFactory();

    @Override
    protected RelationshipGraphConnectionFactory connectionFactory()
        throws Exception
    {
        return connFac;
    }

}