package com.cards.service.service;

import com.cards.common.BadRequestException;
import com.cards.common.ConflictException;
import com.cards.common.Deck;
import com.cards.common.ForbiddenException;
import com.cards.common.NotFoundException;
import com.cards.common.Player;
import com.cards.common.Visibility;
import com.cards.deckimport.ImageProcessor;
import com.cards.deckimport.ImportedCard;
import com.cards.deckimport.NamedBytes;
import com.cards.image.ImageStore;
import com.cards.service.db.CardRepository;
import com.cards.service.db.DeckRepository;
import com.cards.service.db.DeckRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckImportServiceTest {

    private static final String MAPPING = "{\"stats\":[{\"column\":\"rank\",\"label\":\"Rank\",\"higherWins\":false},{\"column\":\"power\",\"label\":\"Power\",\"higherWins\":true}]}";

    private final DeckRepository decks = mock(DeckRepository.class);
    private final CardRepository cards = mock(CardRepository.class);
    private final DeckRequestRepository requests = mock(DeckRequestRepository.class);
    private final ImageStore images = mock(ImageStore.class);
    private final DeckImportService service = new DeckImportService(decks, cards, requests, images, new ImageProcessor(), new ObjectMapper(), 5);
    private final Player ann = Player.builder().id(UUID.randomUUID()).username("ann").build();
    private final UUID deckId = UUID.randomUUID();

    private static String csv(int rows, String imageCell) {
        StringBuilder sb = new StringBuilder("name,image,rank,power\n");
        for (int i = 1; i <= rows; i++) sb.append("Card ").append(i).append(',').append(i <= 2 ? imageCell : "").append(',').append(i).append(',').append(10 + i).append('\n');
        return sb.toString();
    }

    private void deckExists() {
        when(decks.insertDeck(eq(ann.id()), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(deckId);
        when(decks.findById(deckId)).thenReturn(Optional.of(Deck.builder().id(deckId).ownerId(ann.id()).name("My deck").visibility(Visibility.PRIVATE).build()));
    }

    @Test
    void anUploadBecomesAPrivateLiveDeckOwnedByTheUploader() {
        deckExists();
        Deck deck = service.createPrivateDeck(ann, "  My   deck ", " fun ", csv(8, ""), MAPPING, List.of());

        assertEquals(deckId, deck.id());
        verify(decks).insertDeck(ann.id(), "My deck", "fun", Visibility.PRIVATE, true);
        verify(decks).insertStats(eq(deckId), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImportedCard>> saved = ArgumentCaptor.forClass(List.class);
        verify(cards).replaceAll(eq(deckId), saved.capture());
        assertEquals(8, saved.getValue().size());
    }

    @Test
    void uploadedImagesAreStoredOnceEachAndTheCardsPointAtTheirAddress() {
        deckExists();
        UUID imageId = UUID.randomUUID();
        when(images.store(eq(ann.id()), any(), eq("image/jpeg"), anyInt(), anyInt())).thenReturn(imageId);

        service.createPrivateDeck(ann, "My deck", null, csv(8, "a.jpg"), MAPPING, List.of(new NamedBytes("A.JPG", com.cards.deckimport.TestImagesAccess.jpeg(50, 50))));

        verify(images, times(1)).store(eq(ann.id()), any(), eq("image/jpeg"), anyInt(), anyInt());          // two cards use the same file
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImportedCard>> saved = ArgumentCaptor.forClass(List.class);
        verify(cards).replaceAll(eq(deckId), saved.capture());
        assertEquals("/api/images/" + imageId, saved.getValue().get(0).imageUrl());
        assertEquals("/api/images/" + imageId, saved.getValue().get(1).imageUrl());
        assertNull(saved.getValue().get(2).imageUrl());
        assertNull(saved.getValue().get(0).image(), "the pending image is gone once stored");
    }

    @Test
    void nothingIsWrittenWhenTheUploadHasProblems() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.createPrivateDeck(ann, "My deck", null, csv(8, "missing.png"), MAPPING, List.of()));
        assertTrue(e.getMessage().startsWith("Row 1 (Card 1): image 'missing.png' was not uploaded; Row 2 (Card 2)"), e.getMessage());
        verify(decks, never()).insertDeck(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(images, never()).store(any(), any(), any(), anyInt(), anyInt());
        verify(cards, never()).replaceAll(any(), any());
    }

    @Test
    void manyProblemsAreSummarisedAndNameDescriptionAndDeckLimitAreChecked() {
        StringBuilder sb = new StringBuilder("name,rank,power\n");
        for (int i = 1; i <= 20; i++) sb.append("C").append(i).append(",x,y\n");
        assertTrue(assertThrows(BadRequestException.class, () -> service.createPrivateDeck(ann, "My deck", null, sb.toString(), MAPPING, List.of())).getMessage().contains("more problems (use the preview to see them all)"));
        assertEquals("name must be 3 to 40 characters", assertThrows(BadRequestException.class, () -> service.createPrivateDeck(ann, "ab", null, csv(8, ""), MAPPING, List.of())).getMessage());
        assertEquals("description must be at most 300 characters", assertThrows(BadRequestException.class, () -> service.createPrivateDeck(ann, "My deck", "d".repeat(301), csv(8, ""), MAPPING, List.of())).getMessage());
        when(decks.countOwnedBy(ann.id())).thenReturn(5);
        assertEquals("You can have at most 5 decks; delete one first", assertThrows(ConflictException.class, () -> service.createPrivateDeck(ann, "My deck", null, csv(8, ""), MAPPING, List.of())).getMessage());
        verify(decks, never()).insertDeck(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void thePreviewShowsEveryProblemAndTheParsedCardsAndSavesNothing() {
        DeckImportService.Preview good = service.preview(csv(8, ""), MAPPING, List.of());
        assertTrue(good.valid());
        assertEquals(8, good.cards().size());
        assertEquals(List.of("rank", "power"), good.stats().stream().map(DeckImportService.PreviewStat::key).toList());
        assertEquals("INT", good.stats().get(0).valueType());

        DeckImportService.Preview bad = service.preview(csv(8, "nothere.png"), MAPPING, List.of());
        assertFalse(bad.valid());
        assertEquals(2, bad.errorCount());
        assertTrue(bad.cards().isEmpty());
        assertThrows(BadRequestException.class, () -> service.preview(csv(8, ""), "{}", List.of()), "an unusable mapping is a 400 even for a preview");
        verify(decks, never()).insertDeck(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(images, never()).store(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void replacingKeepsTheNameWhenNoneIsGivenAndCleansUpOldImages() {
        Deck existing = Deck.builder().id(deckId).ownerId(ann.id()).name("Old name").description("old").visibility(Visibility.PRIVATE).build();
        when(decks.findById(deckId)).thenReturn(Optional.of(existing));
        UUID oldImage = UUID.randomUUID();
        when(cards.imageUrlsOf(deckId)).thenReturn(List.of("/api/images/" + oldImage, "https://external.example/x.png", "/api/images/" + oldImage));

        service.replaceDeck(ann, deckId, null, null, csv(8, ""), MAPPING, List.of());

        verify(decks).rename(deckId, "Old name", "old");
        verify(decks).deleteStats(deckId);
        verify(decks).insertStats(eq(deckId), any());
        verify(images).deleteUnused(List.of(oldImage));
    }

    @Test
    void deletingRemovesOpenPromotionsTheDeckAndItsUnusedImages() {
        UUID image = UUID.randomUUID();
        when(cards.imageUrlsOf(deckId)).thenReturn(List.of("/api/images/" + image));
        service.deleteDeck(deckId);
        verify(requests).deleteOpenPromotions(deckId);
        verify(decks).deleteDeck(deckId);
        verify(images).deleteUnused(List.of(image));
    }

    @Test
    void onlyTheOwnerMayChangeADeckAndOthersDoNotEvenSeeAPrivateOne() {
        Deck priv = Deck.builder().id(deckId).ownerId(ann.id()).visibility(Visibility.PRIVATE).build();
        Deck pub = Deck.builder().id(deckId).ownerId(ann.id()).visibility(Visibility.PUBLIC).build();
        Player eve = Player.builder().id(UUID.randomUUID()).username("eve").build();
        DeckImportService.requireOwner(priv, ann);
        assertThrows(NotFoundException.class, () -> DeckImportService.requireOwner(priv, eve));
        assertThrows(ForbiddenException.class, () -> DeckImportService.requireOwner(pub, eve));
        assertThrows(NotFoundException.class, () -> DeckImportService.requireOwner(Deck.builder().visibility(Visibility.PRIVATE).build(), eve));
    }
}
