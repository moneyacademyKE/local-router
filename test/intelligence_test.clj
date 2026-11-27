(ns intelligence-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

;; Intelligence for the value chain, keyed by MODEL FAMILY (owner directive
;; 2026-11-27: the budget chain must keep changing to meet its parameters).
;; A feed id and a board entry are both reduced to a family key and joined by
;; exact match; the live board is read in front of the scores this repo
;; recorded on 2026-11-10/11.

(defn run-node-eval [js]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js)]
    (if (zero? exit) (json/parse-string out true)
        (throw (ex-info (str "Node eval error: " err) {:out out})))))

(def ^:private board
  "const board = {board: {id: 'aa-intelligence', publishedOn: '2026-11-26', capturedAt: '2026-11-26 04:31:06'},
    ranking: [
      {rank: 6, model: 'gpt-6-2-sol', name: 'GPT-6.2 Sol', score: 57},
      {rank: 9, model: 'gpt-6-1-terra', name: 'GPT-6.1 Terra', score: 51},
      {rank: 17, model: 'grok-4-7', name: 'Grok 4.7', score: 35},
      {rank: 19, model: 'deepseek-v4-1-flash', name: 'DeepSeek V4.1 Flash 1017', score: 32},
      {rank: 19, model: 'glm-5-3', name: 'GLM-5.3', score: 32},
      {rank: 26, model: 'glm-5-3-flash', name: 'GLM 5.3 Flash', score: 28, verified: true},
      {rank: 31, model: 'mistral-3-5', name: 'Mistral Medium 3.5', score: 5},
      {rank: 36, model: 'nanbeige-4-3', name: 'Nanbeige 4.3', score: 'twenty'}
    ]};")

(deftest a-feed-id-is-reduced-to-its-model-family
  (let [res (run-node-eval
             "import { familyKeys } from './src/router/intelligence.js';
              console.log(JSON.stringify({
                terra: familyKeys('cx/gpt-6.1-terra'), terraOai: familyKeys('oai/gpt-6.1-terra'),
                dated: familyKeys('mv/deepseek-v4-1-flash-1017'), dotted: familyKeys('fw/glm-5p3-flash'),
                nested: familyKeys('cmc/z-ai/glm-5.3-flash'), maker: familyKeys('mv/zai-glm-5-3'),
                upper: familyKeys('mm/MiniMax-M3.1-Flash'), image: familyKeys('alitp/wan2.7-image'),
                version: familyKeys('mai/muse-ember-1.0'), empty: familyKeys(''), junk: familyKeys(7),
                slash: familyKeys('a/b/c/d')}));")]
    (is (= ["gpt61terra"] (:terra res)))
    (is (= (:terra res) (:terraOai res))
        "a score belongs to a model, not to a vendor's path to it: both sellers' ids give one family key")
    (is (= ["deepseekv41flash"] (:dated res)) "a dated build suffix is the same model")
    (is (= ["glm53flash"] (:dotted res)) "glm-5p3 is glm-5.3 with the dot spelled as a p")
    (is (= ["glm53flash"] (:nested res)) "every path segment before the model is dropped, however many there are")
    (is (= ["zaiglm53" "glm53"] (:maker res))
        "a maker written inside the segment is tried second, after the segment as written")
    (is (= ["minimaxm31flash"] (:upper res)))
    (is (= ["wan27image"] (:image res)) "normalising is not judging: an image model gets a key like any other")
    (is (= ["museember10"] (:version res)) "a version suffix is NOT stripped: muse-ember-1.0 is not assumed to be muse-ember")
    (is (= [] (:empty res)))
    (is (= ["7"] (:junk res)))
    (is (= ["d"] (:slash res)))))

(deftest a-score-is-joined-by-exact-family-never-by-resemblance
  (let [res (run-node-eval
             (str
              "import { intelligenceFor, intelligenceFromBoard } from './src/router/intelligence.js';
               " board "
               const b = intelligenceFromBoard(board);
               const pick = (id) => intelligenceFor(id, {board: b});
               console.log(JSON.stringify({board: [b.models, b.verified, b.publishedOn],
                 grok: pick('grok/grok-4.7'), terra: pick('cx/gpt-6.1-terra'), astra: pick('cx/gpt-6-astra'),
                 sol: pick('cx/gpt-6.1-sol'), flash: pick('mv/zai-glm-5-3-flash'), mistral: pick('mv/mistral-medium-3-5'),
                 nearMisses: ['cb/glm-5.2', 'cx/gpt-5.6-luna', 'mm/MiniMax-M3.1-Flash-Preview', 'cb/kimi-k3',
                              'ag/gemini-4-argon', 'zai/glm-5.4', 'cb/deepseek-v4.1-flash-sg'].map(pick),
                 withoutBoard: intelligenceFor('grok/grok-4.7')}));"))]
    (is (= [7 1 "2026-11-26"] (:board res))
        "seven numeric scores, one of them flagged verified by the board itself, with its publication date")
    (is (= {:score 35 :source "board"} (:grok res))
        "the live board's number is read in front of the recorded one (35.5)")
    (is (= {:score 51 :source "board"} (:terra res)))
    (is (= {:score 57 :source "recorded"} (:astra res))
        "a model the board no longer lists keeps the score recorded for it")
    (is (= {:score 55 :source "recorded"} (:sol res))
        "...recorded under another vendor's id: the family is what is looked up")
    (is (= {:score 28 :source "board"} (:flash res)))
    (is (= {:score 5 :source "board"} (:mistral res)) "also joined on the board's display name")
    (is (every? nil? (:nearMisses res))
        "a neighbouring version, a preview, a region variant and an unscored model borrow nothing")
    (is (= {:score 35.5 :source "recorded"} (:withoutBoard res)))))

(deftest a-board-is-only-what-it-claims-to-be
  (let [res (run-node-eval
             "import { intelligenceFromBoard } from './src/router/intelligence.js';
              const nothing = (p) => intelligenceFromBoard(p) === null;
              console.log(JSON.stringify({empty: nothing({}), noRanking: nothing({board: {id: 'aa-intelligence'}}),
                emptyRanking: nothing({ranking: []}), noNumbers: nothing({ranking: [{model: 'x-1', score: 'high'}]}),
                halves: nothing({ranking: [{model: 'x-1'}, {score: 3}]}), notAnObject: nothing('ranking'),
                nullPayload: nothing(null),
                kept: intelligenceFromBoard({ranking: [{model: 'x-1', score: 3}, null, {name: 'Y 2', score: 4}, {model: 'z-3', score: NaN}]}).index}));")]
    (is (every? true? (vals (dissoc res :kept)))
        "an empty, half-shaped or text-scored board is no board: a caller falls back instead of ranking on a guess")
    (is (= {:x1 3 :y2 4} (:kept res))
        "well-formed entries survive beside junk ones, and NaN is not a number")))

(deftest the-board-is-read-once-a-period-and-a-dead-board-never-empties-the-scores
  (let [res (run-node-eval
             (str
              "import { loadIntelligence, INTELLIGENCE_ROW_ID, INTELLIGENCE_PERIOD_MS, INTELLIGENCE_BOARD_URL } from './src/router/intelligence.js';
               " board "
               const period = 500, now = period * INTELLIGENCE_PERIOD_MS + 1000;
               const text = JSON.stringify(board);
               // one run: a D1 that holds `rows`, and a board endpoint that answers `reply()`
               const run = async ({ rows = {}, reply, failWrite = false, withDb = true }) => {
                 const calls = [], writes = [];
                 const db = { prepare(sql) { return { bind(...args) { return {
                   async first() { for (const id of Object.keys(rows)) if (sql.includes(\"'\" + id + \"'\")) return rows[id]; return null; },
                   async run() { writes.push([sql, args]); if (failWrite) throw new Error('D1 is read-only');
                     for (const id of Object.keys(rows)) if (sql.includes(\"'\" + id + \"'\")) rows[id] = {hour: args[0], payload: args[1]}; }
                 }; } }; } };
                 const fetchFn = async (url, opts) => { calls.push([url, opts.headers.Accept]); return reply(); };
                 const loaded = await loadIntelligence(withDb ? { ROUTING_DB: db } : {}, fetchFn, now);
                 return { board: loaded && { models: loaded.models, stale: Boolean(loaded.stale), source: loaded.source }, calls, writes };
               };
               const up = () => new Response(text, {status: 200});
               const down = () => new Response('nope', {status: 500});
               const old = () => ({ [INTELLIGENCE_ROW_ID]: { hour: period - 1, payload: text } });
               const served = await run({ rows: { [INTELLIGENCE_ROW_ID]: { hour: period, payload: text } },
                 reply: () => { throw new Error('a current row is served, never refreshed'); } });
               const refreshed = await run({ rows: old(), reply: up });
               const stale = await run({ rows: old(), reply: down });
               const none = await run({ reply: down });
               const garbage = await run({ reply: () => new Response('{}', {status: 200}) });
               const unwritable = await run({ failWrite: true, reply: up });
               const noDb = await run({ withDb: false, reply: up });
               console.log(JSON.stringify({ served, refreshed, stale, none, garbage, unwritable, noDb }));"))]
    (is (= {:board {:models 7 :stale false :source "https://models.fru.dev/api/boards/aa-intelligence"} :calls [] :writes []}
           (:served res))
        "this period's row is served without a single request to the board")
    (let [{:keys [board calls]} (:refreshed res)]
      (is (= {:models 7 :stale false :source "https://models.fru.dev/api/boards/aa-intelligence"} board))
      (is (= [["https://models.fru.dev/api/boards/aa-intelligence" "application/json"]] calls)
          "an expired row is replaced by one read of the published board"))
    (is (= ["INSERT INTO auto_chain_snapshot (id, hour, payload) VALUES ('auto-intelligence', ?1, ?2) "
            "ON CONFLICT(id) DO UPDATE SET hour=excluded.hour, payload=excluded.payload "
            "WHERE excluded.hour > auto_chain_snapshot.hour"]
           (clojure.string/split (first (first (:writes (:refreshed res)))) #"(?=ON CONFLICT|WHERE )"))
        "the board is kept under its own row id, and an older read can never overwrite a newer one")
    (is (= {:board {:models 7 :stale true :source "https://models.fru.dev/api/boards/aa-intelligence"}
            :calls [["https://models.fru.dev/api/boards/aa-intelligence" "application/json"]] :writes []}
           (:stale res))
        "a dead board serves the last one read, labelled stale")
    (is (nil? (:board (:none res))) "...and with no board on record the caller is told so (it then ranks by recorded scores)")
    (is (nil? (:board (:garbage res))) "a 200 that is not a board is not a board")
    (is (= {:models 7 :stale false :source "https://models.fru.dev/api/boards/aa-intelligence"} (:board (:unwritable res)))
        "a failed write loses nothing: the board that was just read is still returned")
    (is (= {:models 7 :stale false :source "https://models.fru.dev/api/boards/aa-intelligence"} (:board (:noDb res))))
    (is (= 1 (count (:calls (:noDb res)))))))
