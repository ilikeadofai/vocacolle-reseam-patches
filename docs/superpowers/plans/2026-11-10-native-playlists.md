# Native Playlists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Manage the patch's local playlists through VocaColle's original playlist system.

**Architecture:** A resumable copy into native LOCALLIST storage uses generated repository and
coroutine bridges. The original button works with the resulting real native rows; the patch's
save menu reuses the same picker.

**Tech Stack:** Kotlin, Reseam 0.39.1, VocaColle 7.48.0, SharedPreferences migration journal.

**Spec:** `docs/superpowers/specs/2026-11-10-native-playlists-design.md`

## Global Constraints

- Preserve original JSON and unrelated in-progress login investigation changes.
- Never write to account mylists or change login.
- Journal by native library owner; use one background worker.
- Do not treat successful patch application as observed phone UI behavior.

## Review Focus

Same-name native lists must not be merged. Interrupted creation/additions must not duplicate data.
Native deletion after completion must not cause resurrection. Failed metadata retrieval must leave
work pending. A missing/stale native picker must preserve a usable fallback.

## Tasks

- [x] Write failing checks for fresh copy, same-name collision, interrupted creation/addition,
  retry deduplication, completed deletion, order, and independent checkpoint scopes.
- [x] Implement the Android-independent migration engine and run those checks.
- [x] Add runtime journaling, background execution, and structurally resolved native bridges.
- [x] Capture the player's bottom-sheet state and route Save to the original picker.
- [x] Present native-management guidance and retain clearly described JSON backups.
- [x] Build and run checks, apply to the original XAPK, inspect emitted bridges, and record limits.

Implementation and local artifact verification completed. Phone UI checks remain open and are
listed separately in `docs/NATIVE-PLAYLISTS.md`; no device migration is claimed.
