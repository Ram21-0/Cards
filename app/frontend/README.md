# Cards — frontend (web)

Plain HTML/CSS/JS, no build step. Looks and flows like the Android app
(`app/src/main/res`): same colours, card layout, checklist, result text and buttons.

```bash
cd app/frontend
npm start        # http://localhost:8081  (any static server works; or open index.html)
npm test         # engine unit tests (Node 18+)
```

## Layout
- `js/engine.js` — pure game rules + CPU (no DOM, tested)
- `js/app.js` — screens: splash, menu, exhibition/viewer, login, game, game over
- `style.css` — Android colours (`colors.xml`) and card/button styles
- `data/cards.js` — card data (currently **placeholder** wrestlers)
- `assets/placeholder.png` — the `?` silhouette from `drawable/images.png`

## Card data
Real names/images/stats were in the Firebase `Data` node, which isn't in the repo. Replace
`data/cards.js` (or later, load from the backend) with:

```js
window.CARDS = [{ id, name, brand: 'raw' | 'sd', img, rank, fight, chest, biceps, feet, inches, weight }];
```
`brand: 'sd'` renders blue like the Android app; anything else is red. `rank` must be unique; lower wins.
Login is stored locally until the backend exists.
