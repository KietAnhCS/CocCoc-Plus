package com.vnsearch.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiem thu may trang thai cua job crawl (State pattern). */
class CrawlStatusTest {

    @Test
    void startedCanGoToRunningOrFailed() {
        assertTrue(CrawlStatus.STARTED.canTransitionTo(CrawlStatus.RUNNING));
        assertTrue(CrawlStatus.STARTED.canTransitionTo(CrawlStatus.FAILED));
    }

    @Test
    void startedCannotJumpStraightToDone() {
        assertFalse(CrawlStatus.STARTED.canTransitionTo(CrawlStatus.DONE),
                "Phai qua RUNNING truoc khi DONE");
    }

    @Test
    void runningCanGoToDoneOrFailed() {
        assertTrue(CrawlStatus.RUNNING.canTransitionTo(CrawlStatus.DONE));
        assertTrue(CrawlStatus.RUNNING.canTransitionTo(CrawlStatus.FAILED));
    }

    @Test
    void runningCannotGoBackToStarted() {
        assertFalse(CrawlStatus.RUNNING.canTransitionTo(CrawlStatus.STARTED));
    }

    @Test
    void terminalStatesAcceptNoTransition() {
        for (CrawlStatus next : CrawlStatus.values()) {
            assertFalse(CrawlStatus.DONE.canTransitionTo(next), "DONE -> " + next);
            assertFalse(CrawlStatus.FAILED.canTransitionTo(next), "FAILED -> " + next);
        }
    }

    @Test
    void isTerminalIdentifiesEndStates() {
        assertFalse(CrawlStatus.STARTED.isTerminal());
        assertFalse(CrawlStatus.RUNNING.isTerminal());
        assertTrue(CrawlStatus.DONE.isTerminal());
        assertTrue(CrawlStatus.FAILED.isTerminal());
    }

    @Test
    void noStateCanTransitionToItself() {
        for (CrawlStatus status : CrawlStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " -> chinh no");
        }
    }
}
