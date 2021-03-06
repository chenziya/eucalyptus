/*************************************************************************
 * (c) Copyright 2017 Hewlett Packard Enterprise Development Company LP
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 ************************************************************************/
package com.eucalyptus.cassandra.common;

import com.eucalyptus.component.ComponentId;
import com.eucalyptus.component.annotation.FaultLogPrefix;
import com.eucalyptus.component.annotation.Partition;
import com.eucalyptus.component.id.Eucalyptus;

/**
 * Component identifier class for Cassandra
 */
@Partition( Eucalyptus.class )
@FaultLogPrefix( "cloud" )
public class Cassandra extends ComponentId {
  private static final long serialVersionUID = 1L;

  @Override
  public boolean isDistributedService( ) {
    return true;
  }

  @Override
  public boolean isRegisterable( ) {
    return false;
  }

  @Override
  public Integer getPort( ) {
    return 8787;
  }
}

