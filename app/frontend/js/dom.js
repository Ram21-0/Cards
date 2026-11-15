// A tiny element builder shared by the screens: el('button', { class: 'btn', onclick }, 'Label', childNode, ...)
export const el = (tag, props = {}, ...kids) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) n.append(kid?.nodeType ? kid : document.createTextNode(kid ?? ''));
  return n;
};
