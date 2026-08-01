import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue'

const focusableSelector = [
  'a[href]',
  'button:not(:disabled)',
  'input:not(:disabled)',
  'select:not(:disabled)',
  'textarea:not(:disabled)',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

export function useModalFocus(open: Ref<boolean>, container: Ref<HTMLElement | null>, close: () => void) {
  let returnFocus: HTMLElement | null = null

  function focusableElements() {
    return Array.from(container.value?.querySelectorAll<HTMLElement>(focusableSelector) || [])
      .filter((element) => !element.hasAttribute('hidden'))
  }

  function handleKeydown(event: KeyboardEvent) {
    if (!open.value) return
    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }
    if (event.key !== 'Tab') return
    const elements = focusableElements()
    if (!elements.length) {
      event.preventDefault()
      container.value?.focus()
      return
    }
    const first = elements[0]
    const last = elements[elements.length - 1]
    if (!container.value?.contains(document.activeElement)) {
      event.preventDefault()
      const target = event.shiftKey ? last : first
      target?.focus()
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first?.focus()
    }
  }

  watch(open, async (visible) => {
    if (visible) {
      returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      document.addEventListener('keydown', handleKeydown)
      await nextTick()
      const first = focusableElements()[0]
      if (first) first.focus({ preventScroll: true })
      else container.value?.focus({ preventScroll: true })
      return
    }
    document.removeEventListener('keydown', handleKeydown)
    returnFocus?.focus({ preventScroll: true })
    returnFocus = null
  })

  onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))
}
