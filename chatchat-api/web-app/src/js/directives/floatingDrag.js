const DEFAULT_STORAGE_KEY = "chatchat.ui.floatingDragPosition.v2";
const DEFAULT_VIEWPORT_MARGIN = 12;
const DEFAULT_DRAG_THRESHOLD = 4;

const elementStates = new WeakMap();

function boundedPosition(left, top, width, height, margin) {
  const maxLeft = Math.max(margin, window.innerWidth - width - margin);
  const maxTop = Math.max(margin, window.innerHeight - height - margin);
  return {
    left: Math.min(maxLeft, Math.max(margin, left)),
    top: Math.min(maxTop, Math.max(margin, top))
  };
}

function applyPosition(element, position) {
  element.style.left = `${position.left}px`;
  element.style.top = `${position.top}px`;
  element.style.right = "auto";
  element.style.bottom = "auto";
}

function removePointerListeners(state) {
  window.removeEventListener("pointermove", state.onPointerMove);
  window.removeEventListener("pointerup", state.onPointerEnd);
  window.removeEventListener("pointercancel", state.onPointerEnd);
}

function savePosition(state) {
  const rect = state.element.getBoundingClientRect();
  window.localStorage.setItem(state.storageKey, JSON.stringify({ left: rect.left, top: rect.top }));
}

function restorePosition(state) {
  try {
    const stored = JSON.parse(window.localStorage.getItem(state.storageKey) || "null");
    if (!Number.isFinite(stored?.left) || !Number.isFinite(stored?.top)) {
      return;
    }
    const rect = state.element.getBoundingClientRect();
    applyPosition(state.element, boundedPosition(stored.left, stored.top, rect.width, rect.height, state.margin));
  } catch (error) {
    window.localStorage.removeItem(state.storageKey);
  }
}

const floatingDrag = {
  mounted(element, binding) {
    const options = binding.value || {};
    const state = {
      element,
      storageKey: options.storageKey || DEFAULT_STORAGE_KEY,
      margin: Number.isFinite(options.margin) ? options.margin : DEFAULT_VIEWPORT_MARGIN,
      threshold: Number.isFinite(options.threshold) ? options.threshold : DEFAULT_DRAG_THRESHOLD,
      drag: null,
      suppressClick: false
    };

    state.onPointerDown = (event) => {
      if (event.button !== undefined && event.button !== 0) {
        return;
      }
      const rect = element.getBoundingClientRect();
      applyPosition(element, { left: rect.left, top: rect.top });
      state.drag = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        startLeft: rect.left,
        startTop: rect.top,
        width: rect.width,
        height: rect.height,
        moved: false
      };
      window.addEventListener("pointermove", state.onPointerMove, { passive: false });
      window.addEventListener("pointerup", state.onPointerEnd);
      window.addEventListener("pointercancel", state.onPointerEnd);
      event.preventDefault();
    };

    state.onPointerMove = (event) => {
      const drag = state.drag;
      if (!drag || event.pointerId !== drag.pointerId) {
        return;
      }
      const deltaX = event.clientX - drag.startX;
      const deltaY = event.clientY - drag.startY;
      if (!drag.moved && Math.hypot(deltaX, deltaY) >= state.threshold) {
        drag.moved = true;
        element.classList.add("is-dragging");
      }
      if (!drag.moved) {
        return;
      }
      applyPosition(element, boundedPosition(
        drag.startLeft + deltaX,
        drag.startTop + deltaY,
        drag.width,
        drag.height,
        state.margin
      ));
      event.preventDefault();
    };

    state.onPointerEnd = (event) => {
      const drag = state.drag;
      if (!drag || (event.pointerId !== undefined && event.pointerId !== drag.pointerId)) {
        return;
      }
      if (drag.moved) {
        state.suppressClick = true;
        savePosition(state);
        window.setTimeout(() => {
          state.suppressClick = false;
        }, 0);
      }
      state.drag = null;
      element.classList.remove("is-dragging");
      removePointerListeners(state);
    };

    state.onClick = (event) => {
      if (!state.suppressClick) {
        return;
      }
      state.suppressClick = false;
      event.preventDefault();
      event.stopImmediatePropagation();
    };

    state.onResize = () => {
      const rect = element.getBoundingClientRect();
      const position = boundedPosition(rect.left, rect.top, rect.width, rect.height, state.margin);
      applyPosition(element, position);
      savePosition(state);
    };

    element.addEventListener("pointerdown", state.onPointerDown);
    element.addEventListener("click", state.onClick, true);
    window.addEventListener("resize", state.onResize);
    elementStates.set(element, state);
    restorePosition(state);
  },
  unmounted(element) {
    const state = elementStates.get(element);
    if (!state) {
      return;
    }
    removePointerListeners(state);
    window.removeEventListener("resize", state.onResize);
    element.removeEventListener("pointerdown", state.onPointerDown);
    element.removeEventListener("click", state.onClick, true);
    elementStates.delete(element);
  }
};

export default floatingDrag;
