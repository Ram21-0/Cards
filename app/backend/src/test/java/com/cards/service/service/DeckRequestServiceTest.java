package com.cards.service.service;

import com.cards.TestClock;
import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.DeckRequest;
import com.cards.common.DeckRequestStatus;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.TooManyRequestsException;
import com.cards.common.Visibility;
import com.cards.common.DeckRequestType;
import com.cards.deckimport.StatImportParser;
import com.cards.deckimport.StatSpec;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.DeckRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckRequestServiceTest {

    private final DeckRequestRepository requests = mock(DeckRequestRepository.class);
    private final DeckRepository decks = mock(DeckRepository.class);
    private final CardRepository cards = mock(CardRepository.class);
    private final DeckImportService imports = mock(DeckImportService.class);
    private final DeckRequestNotifier notifier = mock(DeckRequestNotifier.class);
    private final TestClock clock = TestClock.atNow();
    private final DeckRequestService service = new DeckRequestService(requests, decks, cards, imports, notifier, clock, "Wrestling");

    private final Player ann = Player.builder().id(UUID.randomUUID()).username("ann").build();
    private final Player admin = Player.builder().id(UUID.randomUUID()).username("boss").build();

    private static List<String> names(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add("Name " + i);
        return list;
    }

    private DeckRequest stored(DeckRequestStatus status) {
        return DeckRequest.builder().id(UUID.randomUUID()).requesterId(ann.id()).requesterUsername("ann").category("Cricket legends")
                .description("d").names(names(5)).status(status).createdAt(clock.instant()).build();
    }

    private DeckRequest expectCreated() {
        DeckRequest created = stored(DeckRequestStatus.PENDING);
        when(requests.insert(eq(ann.id()), anyString(), any(), any(), any())).thenReturn(created.id());
        when(requests.findById(created.id())).thenReturn(Optional.of(created));
        return created;
    }

    // ---------------------------------------------------------------- creating

    @Test
    void aRequestIsCleanedStoredAndTheAdminIsToldOnce() {
        DeckRequest created = expectCreated();

        DeckRequest result = service.create(ann, "  Cricket\n legends  ", "  a   short\tdescription ",
                List.of(" Sachin   Tendulkar ", "sachin tendulkar", "", "Kapil Dev", "Shane\u0000 Warne", "Brian Lara"));

        assertEquals(created, result);
        verify(requests).lockRequester(ann.id());
        verify(requests).insert(eq(ann.id()), eq("Cricket legends"), eq("a short description"),
                eq(List.of("Sachin Tendulkar", "Kapil Dev", "Shane Warne", "Brian Lara")), any());
        verify(notifier, times(1)).created(created);
    }

    @Test
    void anEmptyDescriptionIsStoredAsNull() {
        expectCreated();
        service.create(ann, "Cricket legends", "   ", names(3));
        verify(requests).insert(eq(ann.id()), eq("Cricket legends"), eq(null), any(), any());
    }

    @Test
    void aPlayerWithoutAUsernameCannotRequest() {
        Player nameless = Player.builder().id(UUID.randomUUID()).build();
        assertEquals("Choose a username first", assertThrows(BadRequestException.class, () -> service.create(nameless, "Cricket", null, names(3))).getMessage());
        verify(requests, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void validationHasClearMessagesAndNothingIsStoredOrMailed() {
        assertEquals("category must be 3 to 60 characters", assertThrows(BadRequestException.class, () -> service.create(ann, "ab", null, names(3))).getMessage());
        assertEquals("category must be 3 to 60 characters", assertThrows(BadRequestException.class, () -> service.create(ann, "x".repeat(61), null, names(3))).getMessage());
        assertEquals("category must be 3 to 60 characters", assertThrows(BadRequestException.class, () -> service.create(ann, null, null, names(3))).getMessage());
        assertEquals("description must be at most 500 characters", assertThrows(BadRequestException.class, () -> service.create(ann, "Cricket", "d".repeat(501), names(3))).getMessage());
        assertEquals("names is required", assertThrows(BadRequestException.class, () -> service.create(ann, "Cricket", null, null)).getMessage());
        assertEquals("Suggest 3 to 60 different names", assertThrows(BadRequestException.class, () -> service.create(ann, "Cricket", null, names(2))).getMessage());
        assertEquals("Suggest 3 to 60 different names", assertThrows(BadRequestException.class,
                () -> service.create(ann, "Cricket", null, List.of("A", "a", "B", "b ", " B"))).getMessage(), "duplicates do not count twice");
        assertEquals("Suggest 3 to 60 different names", assertThrows(BadRequestException.class, () -> service.create(ann, "Cricket", null, names(61))).getMessage());
        assertEquals("each name must be at most 60 characters", assertThrows(BadRequestException.class,
                () -> service.create(ann, "Cricket", null, List.of("A", "B", "C".repeat(61)))).getMessage());
        verify(requests, never()).insert(any(), any(), any(), any(), any());
        verify(notifier, never()).created(any());
    }

    @Test
    void sixtyNamesAreAccepted() {
        expectCreated();
        List<String> sixty = new ArrayList<>();
        for (int i = 0; i < 60; i++) sixty.add("N" + i);
        service.create(ann, "Cri", null, sixty);
    }

    @Test
    void threePendingRequestsIsTheLimit() {
        when(requests.countPending(ann.id())).thenReturn(3);
        assertEquals("You already have 3 pending requests; wait for a decision",
                assertThrows(ConflictException.class, () -> service.create(ann, "Cricket", null, names(3))).getMessage());
        verify(requests, never()).insert(any(), any(), any(), any(), any());
        when(requests.countPending(ann.id())).thenReturn(2);
        expectCreated();
        service.create(ann, "Cricket", null, names(3));
    }

    @Test
    void fiveRequestsInTwentyFourHoursIsTheLimit() {
        when(requests.countSince(eq(ann.id()), any())).thenReturn(5);
        assertThrows(TooManyRequestsException.class, () -> service.create(ann, "Cricket", null, names(3)));
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(requests).countSince(eq(ann.id()), since.capture());
        assertEquals(clock.instant().minus(Duration.ofDays(1)), since.getValue(), "a rolling 24 hour window");
        verify(requests, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void youOnlySeeYourOwnRequests() {
        DeckRequest mine = stored(DeckRequestStatus.PENDING);
        when(requests.findById(mine.id())).thenReturn(Optional.of(mine));
        assertEquals(mine, service.mine(ann, mine.id()));
        Player eve = Player.builder().id(UUID.randomUUID()).username("eve").build();
        assertThrows(NotFoundException.class, () -> service.mine(eve, mine.id()));
        assertThrows(NotFoundException.class, () -> service.mine(ann, UUID.randomUUID()));
    }

    // ---------------------------------------------------------------- admin

    @Test
    void approvingAsPublicCreatesTheNotLiveDeckOwnedByTheRequester() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        UUID deckId = UUID.randomUUID();
        when(requests.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(decks.insertNotLive(ann.id(), "Cricket legends", "d")).thenReturn(deckId);
        when(requests.decide(eq(pending.id()), eq(DeckRequestStatus.APPROVED_GLOBAL), eq("go"), eq(admin.id()), eq(deckId), any())).thenReturn(true);
        DeckRequest decided = pending.toBuilder().status(DeckRequestStatus.APPROVED_GLOBAL).resultingDeckId(deckId).build();
        when(requests.findById(pending.id())).thenReturn(Optional.of(decided));

        assertEquals(decided, service.approve(pending.id(), admin, "public", " go "));
        verify(decks).insertNotLive(ann.id(), "Cricket legends", "d");
    }

    @Test
    void approvingAsPrivateIsRecordedAsPrivate() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        when(requests.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(decks.insertNotLive(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(requests.decide(eq(pending.id()), eq(DeckRequestStatus.APPROVED_PRIVATE), any(), any(), any(), any())).thenReturn(true);
        when(requests.findById(pending.id())).thenReturn(Optional.of(pending));
        service.approve(pending.id(), admin, "PRIVATE", null);
        verify(requests).decide(eq(pending.id()), eq(DeckRequestStatus.APPROVED_PRIVATE), eq(null), eq(admin.id()), any(), any());
    }

    @Test
    void theVisibilityMustBePrivateOrPublicAndOnlyPendingRequestsCanBeDecided() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        when(requests.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        for (String bad : new String[]{null, "", "UNLISTED", "global"}) {
            assertEquals("visibility must be PRIVATE or PUBLIC", assertThrows(BadRequestException.class, () -> service.approve(pending.id(), admin, bad, null)).getMessage());
        }
        DeckRequest done = stored(DeckRequestStatus.REJECTED);
        when(requests.findByIdForUpdate(done.id())).thenReturn(Optional.of(done));
        assertEquals("The request is already REJECTED", assertThrows(ConflictException.class, () -> service.approve(done.id(), admin, "PUBLIC", null)).getMessage());
        assertThrows(ConflictException.class, () -> service.reject(done.id(), admin, "no"));
        assertThrows(ConflictException.class, () -> service.edit(done.id(), null, null, names(3)));
        assertThrows(NotFoundException.class, () -> service.approve(UUID.randomUUID(), admin, "PUBLIC", null));
        verify(decks, never()).insertNotLive(any(), any(), any());
    }

    @Test
    void aLostRaceBetweenTwoDecisionsIsAConflict() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        when(requests.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(requests.decide(any(), any(), any(), any(), any(), any())).thenReturn(false);
        assertThrows(ConflictException.class, () -> service.reject(pending.id(), admin, "no"));
    }

    @Test
    void rejectingNeedsANoteAndCreatesNoDeck() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        when(requests.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        assertEquals("A note is required", assertThrows(BadRequestException.class, () -> service.reject(pending.id(), admin, "  ")).getMessage());
        when(requests.decide(eq(pending.id()), eq(DeckRequestStatus.REJECTED), eq("Too narrow"), eq(admin.id()), eq(null), any())).thenReturn(true);
        when(requests.findById(pending.id())).thenReturn(Optional.of(pending));
        service.reject(pending.id(), admin, " Too   narrow ");
        verify(decks, never()).insertNotLive(any(), any(), any());
    }

    @Test
    void theAdminCanEditThePendingNamesWithTheSameCleaning() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        when(requests.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(requests.findById(pending.id())).thenReturn(Optional.of(pending));

        service.edit(pending.id(), null, null, List.of(" A ", "a", "B", "C"));

        verify(requests).updateContent(pending.id(), "Cricket legends", "d", List.of("A", "B", "C"));
        assertThrows(BadRequestException.class, () -> service.edit(pending.id(), null, null, List.of("A", "B")));
    }

    @Test
    void listingFiltersByStatusOrShowsAll() {
        service.list(null);
        service.list("all");
        verify(requests, times(2)).findAll(null);
        service.list("pending");
        verify(requests).findAll(DeckRequestStatus.PENDING);
        assertThrows(BadRequestException.class, () -> service.list("nonsense"));
    }

    // ---------------------------------------------------------------- stage 2a: import and publish

    private static final String CSV = "name,rank,fight,chest,biceps,height,weight,brand\n" + """
            A,1,50,40,16,72,230,raw
            B,2,49,41.5,15.5,73,240,smackdown
            C,3,48,39,15,74,250,
            D,4,47,38,14.5,70,210,raw
            E,5,46,37,14,69,200,raw
            F,6,45,36,13.5,68,190,raw
            G,7,44,35,13,67,180,raw
            H,8,43,34,12.5,66,170,raw
            """;

    private DeckRequest approved(DeckRequestStatus status, int cardCount, boolean live) {
        return stored(status).toBuilder().resultingDeckId(UUID.randomUUID()).cardCount(cardCount).publishedAt(live ? clock.instant() : null).build();
    }

    @Test
    void importingGoesThroughTheSharedPipelineWithTheTemplateDecksStats() {
        DeckRequest r = approved(DeckRequestStatus.APPROVED_GLOBAL, 0, false);
        UUID template = UUID.randomUUID();
        when(requests.findByIdForUpdate(r.id())).thenReturn(Optional.of(r));
        when(requests.findById(r.id())).thenReturn(Optional.of(r));
        when(decks.findTemplateId("Wrestling")).thenReturn(Optional.of(template));
        when(decks.findById(template)).thenReturn(Optional.of(com.cards.common.Deck.builder().id(template).stats(List.of(
                com.cards.common.StatDefinition.builder().key("rank").label("Rank").valueType(com.cards.common.ValueType.INT)
                        .comparisonStrategy(com.cards.common.ComparisonStrategy.LOWER_WINS).displayFormat(com.cards.common.DisplayFormat.PLAIN).displayOrder(1).build())).build()));

        service.importCsv(r.id(), CSV);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StatSpec>> specs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<StatImportParser.Table> table = ArgumentCaptor.forClass(StatImportParser.Table.class);
        verify(imports).fillRequestedDeck(eq(r.resultingDeckId()), eq(r.requesterId()), table.capture(), specs.capture());
        assertEquals(8, table.getValue().rows().size());
        assertEquals("rank", specs.getValue().get(0).key());
        assertEquals(false, specs.getValue().get(0).higherWins());
    }

    @Test
    void jsonCardsBecomeTheSameTable() {
        DeckRequest r = approved(DeckRequestStatus.APPROVED_GLOBAL, 0, false);
        when(requests.findByIdForUpdate(r.id())).thenReturn(Optional.of(r));
        when(requests.findById(r.id())).thenReturn(Optional.of(r));
        UUID template = UUID.randomUUID();
        when(decks.findTemplateId("Wrestling")).thenReturn(Optional.of(template));
        when(decks.findById(template)).thenReturn(Optional.of(com.cards.common.Deck.builder().id(template).stats(List.of()).build()));

        service.importCards(r.id(), List.of(java.util.Map.of("Name", "A", "rank", 1, "chest", 38.5), java.util.Map.of("name", "B", "rank", 2)));

        ArgumentCaptor<StatImportParser.Table> table = ArgumentCaptor.forClass(StatImportParser.Table.class);
        verify(imports).fillRequestedDeck(eq(r.resultingDeckId()), eq(r.requesterId()), table.capture(), any());
        assertEquals("A", table.getValue().rows().get(0).get("name"));
        assertEquals("1", table.getValue().rows().get(0).get("rank"));
        assertThrows(BadRequestException.class, () -> service.importCards(r.id(), List.of()));
    }

    @Test
    void importAndPublishNeedAnApprovedRequestThatIsNotLiveYet() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        DeckRequest rejected = stored(DeckRequestStatus.REJECTED);
        DeckRequest live = approved(DeckRequestStatus.APPROVED_PRIVATE, 9, true);
        for (DeckRequest r : List.of(pending, rejected, live)) when(requests.findByIdForUpdate(r.id())).thenReturn(Optional.of(r));

        assertEquals("Approve the request first", assertThrows(ConflictException.class, () -> service.importCsv(pending.id(), CSV)).getMessage());
        assertEquals("Approve the request first", assertThrows(ConflictException.class, () -> service.publish(rejected.id())).getMessage());
        assertEquals("The deck is already live", assertThrows(ConflictException.class, () -> service.importCsv(live.id(), CSV)).getMessage());
        assertEquals("The deck is already live", assertThrows(ConflictException.class, () -> service.publish(live.id())).getMessage());
        verify(imports, never()).fillRequestedDeck(any(), any(), any(), any());
        verify(decks, never()).publish(any(), any(), any());
    }

    @Test
    void publishingNeedsTheImportedCardsAndUsesTheVisibilityTheAdminChose() {
        DeckRequest empty = approved(DeckRequestStatus.APPROVED_GLOBAL, 0, false);
        when(requests.findByIdForUpdate(empty.id())).thenReturn(Optional.of(empty));
        assertEquals("Import the card stats first (at least 8 cards)", assertThrows(ConflictException.class, () -> service.publish(empty.id())).getMessage());

        for (DeckRequestStatus status : new DeckRequestStatus[]{DeckRequestStatus.APPROVED_GLOBAL, DeckRequestStatus.APPROVED_PRIVATE}) {
            DeckRequest ready = approved(status, 12, false);
            when(requests.findByIdForUpdate(ready.id())).thenReturn(Optional.of(ready));
            when(requests.findById(ready.id())).thenReturn(Optional.of(ready));
            when(decks.statCount(ready.resultingDeckId())).thenReturn(6);
            service.publish(ready.id());
            verify(decks).publish(eq(ready.resultingDeckId()), eq(status == DeckRequestStatus.APPROVED_GLOBAL ? Visibility.PUBLIC : Visibility.PRIVATE), any());
            verify(requests).markPublished(eq(ready.id()), any());
        }
    }

    @Test
    void anUnreadableCsvIsRefusedBeforeAnythingIsWritten() {
        DeckRequest r = approved(DeckRequestStatus.APPROVED_GLOBAL, 0, false);
        when(requests.findByIdForUpdate(r.id())).thenReturn(Optional.of(r));
        assertThrows(BadRequestException.class, () -> service.importCsv(r.id(), "  "));
        verify(imports, never()).fillRequestedDeck(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- promotion of a private deck

    private com.cards.common.Deck privateDeck(int cards) {
        return com.cards.common.Deck.builder().id(UUID.randomUUID()).ownerId(ann.id()).name("My deck").description("d").visibility(Visibility.PRIVATE)
                .cardCount(cards).stats(List.of(com.cards.common.StatDefinition.builder().key("rank").build())).build();
    }

    @Test
    void theOwnerAsksToMakeAPrivateDeckGlobalAndTheAdminIsMailed() {
        com.cards.common.Deck deck = privateDeck(9);
        when(decks.findById(deck.id())).thenReturn(Optional.of(deck));
        when(cards.findByDeck(deck.id())).thenReturn(List.of(com.cards.common.Card.builder().name("A").build(), com.cards.common.Card.builder().name("B").build()));
        UUID id = UUID.randomUUID();
        DeckRequest created = stored(DeckRequestStatus.PENDING).toBuilder().id(id).type(DeckRequestType.PROMOTE).resultingDeckId(deck.id()).build();
        when(requests.insertPromotion(eq(ann.id()), eq(deck.id()), eq("My deck"), eq("d"), eq(List.of("A", "B")), any())).thenReturn(id);
        when(requests.findById(id)).thenReturn(Optional.of(created));

        assertEquals(created, service.createPromotion(ann, deck.id()));
        verify(notifier).created(created);
    }

    @Test
    void promotionNeedsTheOwnerAPrivateReadyDeckAndOnlyOneOpenRequest() {
        com.cards.common.Deck deck = privateDeck(9);
        when(decks.findById(deck.id())).thenReturn(Optional.of(deck));
        Player eve = Player.builder().id(UUID.randomUUID()).username("eve").build();
        assertThrows(NotFoundException.class, () -> service.createPromotion(eve, deck.id()));
        assertEquals("Choose a username first", assertThrows(BadRequestException.class, () -> service.createPromotion(Player.builder().id(ann.id()).build(), deck.id())).getMessage());

        com.cards.common.Deck pub = privateDeck(9).toBuilder().visibility(Visibility.PUBLIC).build();
        when(decks.findById(pub.id())).thenReturn(Optional.of(pub));
        assertEquals("The deck is already public", assertThrows(ConflictException.class, () -> service.createPromotion(ann, pub.id())).getMessage());
        com.cards.common.Deck small = privateDeck(5);
        when(decks.findById(small.id())).thenReturn(Optional.of(small));
        assertEquals("The deck needs at least 8 cards", assertThrows(ConflictException.class, () -> service.createPromotion(ann, small.id())).getMessage());

        when(requests.hasOpenPromotion(deck.id())).thenReturn(true);
        assertEquals("A request to make this deck global is already open", assertThrows(ConflictException.class, () -> service.createPromotion(ann, deck.id())).getMessage());
        verify(requests, never()).insertPromotion(any(), any(), any(), any(), any(), any());
        verify(notifier, never()).created(any());
    }

    @Test
    void promotionRequestsCountTowardTheSameLimits() {
        com.cards.common.Deck deck = privateDeck(9);
        when(decks.findById(deck.id())).thenReturn(Optional.of(deck));
        when(requests.countPending(ann.id())).thenReturn(3);
        assertThrows(ConflictException.class, () -> service.createPromotion(ann, deck.id()));
        when(requests.countPending(ann.id())).thenReturn(0);
        when(requests.countSince(eq(ann.id()), any())).thenReturn(5);
        assertThrows(TooManyRequestsException.class, () -> service.createPromotion(ann, deck.id()));
    }

    @Test
    void approvingAPromotionMakesTheDeckPublicAndPublishedAtOnce() {
        UUID deckId = UUID.randomUUID();
        DeckRequest promo = stored(DeckRequestStatus.PENDING).toBuilder().type(DeckRequestType.PROMOTE).resultingDeckId(deckId).build();
        when(requests.findByIdForUpdate(promo.id())).thenReturn(Optional.of(promo));
        when(requests.findById(promo.id())).thenReturn(Optional.of(promo));
        when(requests.decide(eq(promo.id()), eq(DeckRequestStatus.APPROVED_GLOBAL), eq("fine"), eq(admin.id()), eq(deckId), any())).thenReturn(true);

        service.approve(promo.id(), admin, null, "fine");                          // visibility may be left out: a promotion is always PUBLIC

        verify(decks).publish(eq(deckId), eq(Visibility.PUBLIC), any());
        verify(requests).markPublished(eq(promo.id()), any());
        verify(decks, never()).insertNotLive(any(), any(), any());
        assertEquals("A promotion can only be approved as PUBLIC", assertThrows(BadRequestException.class, () -> service.approve(promo.id(), admin, "PRIVATE", null)).getMessage());
    }

    @Test
    void rejectingAPromotionLeavesTheDeckPrivateAndAPromotionCannotBeImportedPublishedOrEdited() {
        UUID deckId = UUID.randomUUID();
        DeckRequest promo = stored(DeckRequestStatus.PENDING).toBuilder().type(DeckRequestType.PROMOTE).resultingDeckId(deckId).build();
        when(requests.findByIdForUpdate(promo.id())).thenReturn(Optional.of(promo));
        when(requests.findById(promo.id())).thenReturn(Optional.of(promo));
        when(requests.decide(eq(promo.id()), eq(DeckRequestStatus.REJECTED), eq("Not now"), eq(admin.id()), eq(null), any())).thenReturn(true);
        service.reject(promo.id(), admin, "Not now");
        verify(decks, never()).publish(any(), any(), any());

        assertThrows(ConflictException.class, () -> service.edit(promo.id(), null, null, names(3)));
        assertEquals("This is a promotion request: approve or reject it", assertThrows(ConflictException.class, () -> service.importCsv(promo.id(), CSV)).getMessage());
        assertEquals("This is a promotion request: approve or reject it", assertThrows(ConflictException.class, () -> service.publish(promo.id())).getMessage());
    }

    @Test
    void aPendingRequestCanBeWithdrawnByItsRequesterOnly() {
        DeckRequest pending = stored(DeckRequestStatus.PENDING);
        when(requests.findById(pending.id())).thenReturn(Optional.of(pending));
        when(requests.deletePending(pending.id(), ann.id())).thenReturn(true);
        service.withdraw(ann, pending.id());
        verify(requests).deletePending(pending.id(), ann.id());

        Player eve = Player.builder().id(UUID.randomUUID()).username("eve").build();
        assertThrows(NotFoundException.class, () -> service.withdraw(eve, pending.id()));
        DeckRequest done = stored(DeckRequestStatus.REJECTED);
        when(requests.findById(done.id())).thenReturn(Optional.of(done));
        assertEquals("Only a pending request can be withdrawn", assertThrows(ConflictException.class, () -> service.withdraw(ann, done.id())).getMessage());
    }
}
