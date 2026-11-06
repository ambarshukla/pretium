"use client";

import { useEffect, useState } from "react";

const STORAGE = "pretium-review-key";

/** The review key, kept in this browser only so it need not be typed on every page. */
export function useReviewKey(): [string, (k: string) => void] {
  const [key, setKey] = useState("");
  useEffect(() => {
    setKey(window.localStorage.getItem(STORAGE) ?? "");
  }, []);
  return [
    key,
    (k: string) => {
      setKey(k);
      window.localStorage.setItem(STORAGE, k);
    },
  ];
}
