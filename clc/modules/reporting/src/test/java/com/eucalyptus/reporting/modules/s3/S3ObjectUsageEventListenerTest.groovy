package com.eucalyptus.reporting.modules.s3

import org.junit.Test
import com.eucalyptus.auth.principal.Principals

import static org.junit.Assert.*
import com.eucalyptus.reporting.domain.ReportingAccountCrud
import com.eucalyptus.reporting.domain.ReportingUserCrud
import com.eucalyptus.auth.principal.User
import com.google.common.base.Charsets
import com.eucalyptus.reporting.event.S3ObjectEvent
import com.eucalyptus.reporting.event_store.ReportingS3ObjectEventStore
import com.eucalyptus.reporting.event_store.ReportingS3ObjectCreateEvent
import com.eucalyptus.reporting.event_store.ReportingS3ObjectDeleteEvent
import com.eucalyptus.reporting.event_store.ReportingS3ObjectUsageEvent

/**
 * 
 */
class S3ObjectUsageEventListenerTest {

  @Test
  void testInstantiable() {
    new S3BucketUsageEventListener()
  }

  @Test
  void testCreateEvent() {
    long timestamp = System.currentTimeMillis() - 100000

    Object persisted = testEvent( S3ObjectEvent.with(
        S3ObjectEvent.forS3ObjectCreate(),
        uuid("????"),
        "bucket15",
        "object34",
        Principals.systemFullName(),
        Integer.MAX_VALUE.toLong() + 1L
    ), timestamp )

    assertTrue( "Persisted event is ReportingS3BucketCreateEvent", persisted instanceof ReportingS3ObjectCreateEvent )
    ReportingS3ObjectCreateEvent event = persisted
    assertEquals( "Persisted event bucket name", "bucket15", event.getS3BucketName() )
    assertEquals( "Persisted event object name", "object34", event.getS3ObjectName() )
    assertEquals( "Persisted event size", Integer.MAX_VALUE.toLong() + 1L, event.getS3ObjectSize() )
    assertEquals( "Persisted event user id", Principals.systemFullName().getUserId(), event.getUserId() )
    assertEquals( "Persisted event timestamp", timestamp, event.getTimestampMs() )
  }

  @Test
  void testDeleteEvent() {
    long timestamp = System.currentTimeMillis() - 100000

    Object persisted = testEvent( S3ObjectEvent.with(
        S3ObjectEvent.forS3ObjectDelete(),
        uuid("????"),
        "bucket15",
        "object34",
        Principals.systemFullName(),
        Integer.MAX_VALUE.toLong() + 1L
    ), timestamp )

    assertTrue( "Persisted event is ReportingS3BucketDeleteEvent", persisted instanceof ReportingS3ObjectDeleteEvent )
    ReportingS3ObjectDeleteEvent event = persisted
    assertEquals( "Persisted event bucket name", "bucket15", event.getS3BucketName() )
    assertEquals( "Persisted event object name", "object34", event.getS3ObjectName() )
    assertEquals( "Persisted event size", Integer.MAX_VALUE.toLong() + 1L, event.getS3ObjectSize() )
    assertEquals( "Persisted event user id", Principals.systemFullName().getUserId(), event.getS3ObjectOwnerId() )
    assertEquals( "Persisted event timestamp", timestamp, event.getTimestampMs() )
  }

  @Test
  void testGetEvent() {
    long timestamp = System.currentTimeMillis() - 100000

    Object persisted = testEvent( S3ObjectEvent.with(
        S3ObjectEvent.forS3ObjectGet(),
        uuid("????"),
        "bucket15",
        "object34",
        Principals.systemFullName(),
        Integer.MAX_VALUE.toLong() + 1L
    ), timestamp )

    assertTrue( "Persisted event is ReportingS3BucketDeleteEvent", persisted instanceof ReportingS3ObjectUsageEvent )
    ReportingS3ObjectUsageEvent event = persisted
    assertEquals( "Persisted event bucket name", "bucket15", event.getBucketName() )
    assertEquals( "Persisted event object name", "object34", event.getObjectName() )
    assertEquals( "Persisted event size", Integer.MAX_VALUE.toLong() + 1L, event.getObject_size() )
    assertEquals( "Persisted event user id", Principals.systemFullName().getUserId(), event.getUserId() )
    assertEquals( "Persisted event timestamp", timestamp, event.getTimestampMs() )
  }

  private Object testEvent( S3ObjectEvent event, long timestamp ) {
    String updatedAccountId = null
    String updatedAccountName = null
    String updatedUserId = null
    String updatedUserName = null
    Object persisted = null
    ReportingAccountCrud accountCrud = new ReportingAccountCrud( ) {
      @Override void createOrUpdateAccount( String id, String name ) {
        updatedAccountId = id
        updatedAccountName = name
      }
    }
    ReportingUserCrud userCrud = new ReportingUserCrud( ) {
      @Override void createOrUpdateUser( String id, String accountId, String name ) {
        updatedUserId = id
        updatedUserName = name
      }
    }
    ReportingS3ObjectEventStore eventStore = new ReportingS3ObjectEventStore( ) {
      @Override protected void persist( final Object o ) {
        persisted = o
      }
    }
    S3ObjectUsageEventListener listener = new S3ObjectUsageEventListener( ) {
      @Override protected ReportingAccountCrud getReportingAccountCrud() { return accountCrud }
      @Override protected ReportingUserCrud getReportingUserCrud() { return userCrud }
      @Override protected ReportingS3ObjectEventStore getReportingS3ObjectEventStore() { eventStore }
      @Override protected long getCurrentTimeMillis() { timestamp }
      @Override protected User lookupUser( final String userId ) {
        assertEquals( "Looked up user", "eucalyptus", userId )
        Principals.systemUser()
      }
    }

    listener.fireEvent( event )

    assertNotNull( "Persisted event", persisted )
    assertEquals( "Account Id", "eucalyptus", updatedAccountId  )
    assertEquals( "Account Name", "000000000000", updatedAccountName )
    assertEquals( "User Id", "eucalyptus", updatedUserId )
    assertEquals( "User Name", "eucalyptus", updatedUserName )

    persisted
  }

  private String uuid( String seed ) {
    return UUID.nameUUIDFromBytes( seed.getBytes(Charsets.UTF_8) ).toString()
  }
}
