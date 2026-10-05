import { afterEach, describe, expect, it } from "vitest";
import css from "./styles.css?raw";

afterEach(() => {
  document.head.replaceChildren();
  document.body.replaceChildren();
});

// jsdom can't hover or show focus rings, so drop the pseudo-class from each rule that has it and
// see which ones still match.
function rulesMatching(element: Element, pseudo: ":hover" | ":focus-visible"): CSSStyleRule[] {
  const style = document.createElement("style");
  style.textContent = css;
  document.head.append(style);

  const matching: CSSStyleRule[] = [];
  const walk = (rules: CSSRuleList) => {
    for (const rule of Array.from(rules)) {
      if (rule instanceof CSSStyleRule && rule.selectorText.includes(pseudo)) {
        if (element.matches(rule.selectorText.replaceAll(pseudo, "") || "*")) matching.push(rule);
      } else if (rule instanceof CSSGroupingRule) {
        walk(rule.cssRules);
      }
    }
  };
  walk(style.sheet!.cssRules);
  return matching;
}

function hoverRulesMatching(element: Element): string[] {
  return rulesMatching(element, ":hover").map((rule) => rule.selectorText);
}

/** The outline width set by the last matching :focus-visible rule that sets one. */
function focusRingWidth(element: Element): string | undefined {
  return rulesMatching(element, ":focus-visible")
    .map((rule) => rule.style.getPropertyValue("outline-width") || rule.style.getPropertyValue("outline").split(" ")[0])
    .filter(Boolean)
    .at(-1);
}

function button(className: string, busy: boolean): HTMLButtonElement {
  const element = document.createElement("button");
  element.className = className;
  if (busy) element.setAttribute("aria-disabled", "true");
  document.body.append(element);
  return element;
}

describe("button hover styles", () => {
  it("apply to a primary button that is not busy", () => {
    // The base .button rule and the .button--primary one.
    expect(hoverRulesMatching(button("button button--primary", false))).toHaveLength(2);
  });

  it.each([
    ["plain", "button"],
    ["primary", "button button--primary"],
    ["quiet", "button button--quiet"],
    ["danger", "button button--danger"],
    ["link", "link"],
  ])("leave a busy %s button alone, so its label stays readable", (_kind, className) => {
    expect(hoverRulesMatching(button(className, true))).toEqual([]);
  });
});

describe("focus rings", () => {
  it("are 2px on controls", () => {
    expect(focusRingWidth(button("button button--primary", false))).toBe("2px");
  });

  it.each([
    ["session-expired notice", "p", "notice notice--info"],
    ["Habits heading", "h1", "habits__title"],
  ])("are thinner on the %s, which only code focuses", (_kind, tag, className) => {
    const element = document.createElement(tag);
    element.className = className;
    element.tabIndex = -1;
    document.body.append(element);
    expect(focusRingWidth(element)).toBe("1px");
  });
});
