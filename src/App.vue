<script setup lang="ts">
import { computed, ref, watch } from 'vue'

type AppTab = 'overview' | 'content' | 'leads' | 'roadmap'
type LeadStage = 'new' | 'qualified' | 'quoted' | 'sampling' | 'won'
type PriorityBand = 'watch' | 'active' | 'hot'

interface CompanyProfile {
  companyName: string
  productLine: string
  valueProposition: string
  targetMarkets: string
  idealBuyers: string
  trustSignals: string
  certifications: string
  coreOffer: string
  responsePromise: string
}

interface LeadRecord {
  id: number
  company: string
  market: string
  source: string
  contact: string
  demand: string
  annualVolumeUsd: number
  urgency: number
  stage: LeadStage
  lastTouch: string
  nextAction: string
}

const STORAGE_KEYS = {
  profile: 'mengwei-trade-mvp-profile',
  leads: 'mengwei-trade-mvp-leads',
}

const tabs: Array<{ key: AppTab; label: string; eyebrow: string }> = [
  { key: 'overview', label: 'Command Center', eyebrow: 'MVP' },
  { key: 'content', label: 'Content Studio', eyebrow: 'Assets' },
  { key: 'leads', label: 'Leads Desk', eyebrow: 'Pipeline' },
  { key: 'roadmap', label: 'Next Sprint', eyebrow: 'Plan' },
]

const stageLabels: Record<LeadStage, string> = {
  new: 'New',
  qualified: 'Qualified',
  quoted: 'Quoted',
  sampling: 'Sampling',
  won: 'Won',
}

const defaultProfile: CompanyProfile = {
  companyName: 'North Harbor Industrial',
  productLine: 'Custom aluminum packaging and contract manufacturing',
  valueProposition: 'Fast OEM response for buyers who need low-MOQ pilots before scaling full container orders.',
  targetMarkets: 'United States, Germany, UAE, Australia',
  idealBuyers: 'Importers, private-label operators, regional distributors, sourcing managers',
  trustSignals: 'Own tooling team, bilingual sales support, QC photo reports, 18-day pilot turnaround',
  certifications: 'ISO 9001, BSCI, REACH, FSC-ready supply chain',
  coreOffer: 'OEM packaging, sample runs, packaging redesign, freight-ready compliance docs',
  responsePromise: 'Reply with shortlist, quote path, and next step within 12 business hours',
}

const defaultLeads: LeadRecord[] = [
  {
    id: 101,
    company: 'Bright Peak Retail Group',
    market: 'United States',
    source: 'Alibaba RFQ',
    contact: 'Taylor Morgan',
    demand: 'Needs private-label aluminum jars with custom embossing for Q4 launch',
    annualVolumeUsd: 180000,
    urgency: 4,
    stage: 'qualified',
    lastTouch: '2026-07-07',
    nextAction: 'Send pilot MOQ options and embossing lead time',
  },
  {
    id: 102,
    company: 'VitaHaus Distribution',
    market: 'Germany',
    source: 'LinkedIn outbound',
    contact: 'Anja Fischer',
    demand: 'Looking for backup supplier with EU compliance documents and flexible carton labels',
    annualVolumeUsd: 260000,
    urgency: 5,
    stage: 'quoted',
    lastTouch: '2026-07-08',
    nextAction: 'Follow up on quote and attach REACH test summary',
  },
  {
    id: 103,
    company: 'Sahara Care Trading',
    market: 'UAE',
    source: 'Website form',
    contact: 'Omar Rahman',
    demand: 'Distributor wants bundled skincare packaging and mixed-SKU trial shipment',
    annualVolumeUsd: 90000,
    urgency: 3,
    stage: 'new',
    lastTouch: '2026-07-06',
    nextAction: 'Qualify carton mix, destination port, and target retail channels',
  },
  {
    id: 104,
    company: 'Southern Shelf Pty',
    market: 'Australia',
    source: 'Trade show follow-up',
    contact: 'Nina Walsh',
    demand: 'Sampling request for eco-forward line with matte finish and low MOQ',
    annualVolumeUsd: 70000,
    urgency: 4,
    stage: 'sampling',
    lastTouch: '2026-07-05',
    nextAction: 'Confirm courier account and send updated sample invoice',
  },
  {
    id: 105,
    company: 'Nordline Consumer',
    market: 'Netherlands',
    source: 'Referral',
    contact: 'Joris de Vries',
    demand: 'Needs alternate vendor for holiday replenishment with faster carton artwork approval',
    annualVolumeUsd: 320000,
    urgency: 5,
    stage: 'won',
    lastTouch: '2026-07-04',
    nextAction: 'Prepare onboarding checklist and production kickoff notes',
  },
]

const activeTab = ref<AppTab>('overview')
const profile = ref<CompanyProfile>(loadStoredProfile())
const leads = ref<LeadRecord[]>(loadStoredLeads())

const leadForm = ref({
  company: '',
  market: '',
  source: '',
  contact: '',
  demand: '',
  annualVolumeUsd: 50000,
  urgency: 3,
})

const selectedMarket = ref('')
const selectedAsset = ref('reply')

watch(
  profile,
  (value) => {
    localStorage.setItem(STORAGE_KEYS.profile, JSON.stringify(value))
  },
  { deep: true },
)

watch(
  leads,
  (value) => {
    localStorage.setItem(STORAGE_KEYS.leads, JSON.stringify(value))
  },
  { deep: true },
)

const marketList = computed(() => splitList(profile.value.targetMarkets))
const buyerList = computed(() => splitList(profile.value.idealBuyers))
const signalList = computed(() => splitList(profile.value.trustSignals))
const certificateList = computed(() => splitList(profile.value.certifications))

const averageUrgency = computed(() => {
  if (!leads.value.length) return 0
  const total = leads.value.reduce((sum, lead) => sum + lead.urgency, 0)
  return (total / leads.value.length).toFixed(1)
})

const activePipelineValue = computed(() =>
  leads.value
    .filter((lead) => lead.stage !== 'won')
    .reduce((sum, lead) => sum + lead.annualVolumeUsd, 0)
    .toLocaleString('en-US'),
)

const hotLeadCount = computed(() => leads.value.filter((lead) => leadPriority(lead) === 'hot').length)

const readinessCards = computed(() => [
  {
    label: 'Market Story',
    value: `${marketList.value.length} live markets`,
    detail: 'Keep hero messaging country-specific and proof-first.',
  },
  {
    label: 'Sales Reply SLA',
    value: profile.value.responsePromise,
    detail: 'Treat this as the promise every inquiry should feel.',
  },
  {
    label: 'Trust Stack',
    value: `${signalList.value.length + certificateList.value.length} proof anchors`,
    detail: 'Use certifications plus operating signals in every quote trail.',
  },
])

const generatedAssets = computed(() => {
  const market = selectedMarket.value || marketList.value[0] || 'your priority market'
  const company = profile.value.companyName
  const productLine = profile.value.productLine
  const valueProposition = profile.value.valueProposition
  const trust = signalList.value.slice(0, 3).join(', ')
  const certifications = certificateList.value.slice(0, 2).join(', ')
  const buyers = buyerList.value.slice(0, 2).join(' and ')
  const offer = profile.value.coreOffer

  return [
    {
      key: 'reply',
      title: 'Inquiry Reply',
      summary: 'First-touch response for inbound buyers who need clarity fast.',
      body: `Hi {{first_name}},\n\nThanks for reaching out about ${productLine}. We usually support ${buyers || 'buyers'} in ${market} with ${offer.toLowerCase()}.\n\nA fast next step from our side:\n- confirm your target SKU and packaging spec\n- align sample or pilot MOQ\n- share quote path and estimated lead time\n\nWhy buyers stay with ${company}:\n- ${valueProposition}\n- ${trust || 'bilingual support and QC reporting'}\n- ${certifications || 'document-ready export process'}\n\nIf you send target quantity, destination port, and artwork status, we can move this into a concrete quote quickly.\n\nBest,\n{{sales_rep}}`,
    },
    {
      key: 'email',
      title: 'Distributor Outreach Email',
      summary: 'Cold outbound angle for distributors and importers.',
      body: `Subject: Backup OEM option for ${market} buyers needing faster pilot runs\n\nHi {{first_name}},\n\nI work with ${company}. We help ${buyers || 'importers'} who need ${productLine.toLowerCase()} without waiting through slow tooling and rigid MOQs.\n\nThe reason I thought of you: ${valueProposition}\n\nWhat usually gets attention in the first call:\n- pilot order path before full rollout\n- QC photo reports before shipment\n- export paperwork aligned early\n\nIf it helps, I can send a one-page capability sheet plus 2 sample use cases for ${market}.`,
    },
    {
      key: 'landing',
      title: 'Landing Page Hero',
      summary: 'Top-of-page positioning block for market-specific pages.',
      body: `${company}\n\n${productLine}\nfor ${market} buyers who need faster OEM decisions.\n\n${valueProposition}\n\nProof points: ${trust || 'sample turnaround, QC reporting, bilingual handoff'}.\nPrimary CTA: Request pricing path\nSecondary CTA: Ask for pilot MOQ`,
    },
    {
      key: 'faq',
      title: 'GEO / SEO FAQ Block',
      summary: 'Search-facing content for product page and AI-answer surfaces.',
      body: `FAQ 1: What is the typical MOQ for custom ${productLine.toLowerCase()}?\nAnswer: Start with the sample-run and pilot order logic, then show the scaling breakpoints clearly.\n\nFAQ 2: How quickly can ${company} issue export-ready quotes?\nAnswer: ${profile.value.responsePromise} when quantity, spec, and destination are clear.\n\nFAQ 3: Why do overseas buyers choose ${company}?\nAnswer: ${valueProposition} Supported by ${trust || 'operational proof'} and ${certifications || 'recognized compliance documents'}.`,
    },
  ]
})

const selectedAssetDraft = computed(() => {
  return generatedAssets.value.find((asset) => asset.key === selectedAsset.value) ?? generatedAssets.value[0]
})

const groupedLeads = computed(() =>
  (Object.keys(stageLabels) as LeadStage[]).map((stage) => ({
    stage,
    label: stageLabels[stage],
    items: leads.value.filter((lead) => lead.stage === stage),
  })),
)

const topLeads = computed(() =>
  [...leads.value].sort((left, right) => leadScore(right) - leadScore(left)).slice(0, 3),
)

function loadStoredProfile(): CompanyProfile {
  const raw = localStorage.getItem(STORAGE_KEYS.profile)
  if (!raw) return { ...defaultProfile }
  try {
    return { ...defaultProfile, ...JSON.parse(raw) }
  } catch {
    return { ...defaultProfile }
  }
}

function loadStoredLeads(): LeadRecord[] {
  const raw = localStorage.getItem(STORAGE_KEYS.leads)
  if (!raw) return [...defaultLeads]
  try {
    return JSON.parse(raw)
  } catch {
    return [...defaultLeads]
  }
}

function splitList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function leadScore(lead: LeadRecord): number {
  const stageWeight: Record<LeadStage, number> = {
    new: 6,
    qualified: 15,
    quoted: 24,
    sampling: 20,
    won: 30,
  }

  return Math.round(lead.annualVolumeUsd / 10000 + lead.urgency * 8 + stageWeight[lead.stage])
}

function leadPriority(lead: LeadRecord): PriorityBand {
  const score = leadScore(lead)
  if (score >= 70) return 'hot'
  if (score >= 42) return 'active'
  return 'watch'
}

function priorityLabel(lead: LeadRecord): string {
  const priority = leadPriority(lead)
  if (priority === 'hot') return 'Hot'
  if (priority === 'active') return 'Active'
  return 'Watch'
}

function updateLeadStage(leadId: number, stage: LeadStage): void {
  leads.value = leads.value.map((lead) => (lead.id === leadId ? { ...lead, stage } : lead))
}

function resetWorkspace(): void {
  profile.value = { ...defaultProfile }
  leads.value = [...defaultLeads]
}

function addLead(): void {
  if (!leadForm.value.company || !leadForm.value.market || !leadForm.value.demand) return

  leads.value = [
    {
      id: Date.now(),
      company: leadForm.value.company,
      market: leadForm.value.market,
      source: leadForm.value.source || 'Manual entry',
      contact: leadForm.value.contact || 'Unknown contact',
      demand: leadForm.value.demand,
      annualVolumeUsd: leadForm.value.annualVolumeUsd,
      urgency: leadForm.value.urgency,
      stage: 'new',
      lastTouch: new Date().toISOString().slice(0, 10),
      nextAction: 'Qualify SKU, MOQ, and destination port',
    },
    ...leads.value,
  ]

  leadForm.value = {
    company: '',
    market: '',
    source: '',
    contact: '',
    demand: '',
    annualVolumeUsd: 50000,
    urgency: 3,
  }
}
</script>

<template>
  <div class="shell">
    <section class="hero-band">
      <div class="hero-copy">
        <p class="eyebrow">Foreign Trade MVP</p>
        <h1>Mengwei Trade Workbench</h1>
        <p class="lede">
          A local-first operating desk for export teams who need sharper market copy, faster inquiry handling,
          and a clearer view of which buyers deserve the next call.
        </p>
      </div>

      <div class="hero-metrics">
        <article class="metric-card">
          <span>Pipeline</span>
          <strong>${{ activePipelineValue }}</strong>
          <small>Open opportunities tracked locally</small>
        </article>
        <article class="metric-card">
          <span>Hot leads</span>
          <strong>{{ hotLeadCount }}</strong>
          <small>Ready for same-day follow-up</small>
        </article>
        <article class="metric-card">
          <span>Urgency avg</span>
          <strong>{{ averageUrgency }}</strong>
          <small>Buyer pressure across current queue</small>
        </article>
      </div>
    </section>

    <nav class="tab-row" aria-label="Workbench tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-chip"
        :class="{ 'is-active': activeTab === tab.key }"
        type="button"
        @click="activeTab = tab.key"
      >
        <span>{{ tab.eyebrow }}</span>
        <strong>{{ tab.label }}</strong>
      </button>
    </nav>

    <main class="workspace">
      <template v-if="activeTab === 'overview'">
        <section class="panel profile-panel">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Company Profile</p>
              <h2>Base material for every quote, page, and reply</h2>
            </div>
            <button class="ghost-button" type="button" @click="resetWorkspace">Reset demo data</button>
          </div>

          <div class="profile-grid">
            <label>
              Company name
              <input v-model="profile.companyName" type="text" />
            </label>
            <label>
              Product line
              <input v-model="profile.productLine" type="text" />
            </label>
            <label class="full-span">
              Value proposition
              <textarea v-model="profile.valueProposition" rows="3" />
            </label>
            <label>
              Target markets
              <textarea v-model="profile.targetMarkets" rows="3" />
            </label>
            <label>
              Ideal buyers
              <textarea v-model="profile.idealBuyers" rows="3" />
            </label>
            <label>
              Trust signals
              <textarea v-model="profile.trustSignals" rows="3" />
            </label>
            <label>
              Certifications
              <textarea v-model="profile.certifications" rows="3" />
            </label>
            <label>
              Core offer
              <textarea v-model="profile.coreOffer" rows="3" />
            </label>
            <label>
              Response promise
              <input v-model="profile.responsePromise" type="text" />
            </label>
          </div>
        </section>

        <section class="panel readiness-panel">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Readiness</p>
              <h2>What the MVP is already good enough to support</h2>
            </div>
          </div>

          <div class="readiness-grid">
            <article v-for="card in readinessCards" :key="card.label" class="readiness-card">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
              <p>{{ card.detail }}</p>
            </article>
          </div>

          <div class="signal-grid">
            <article class="signal-card">
              <h3>Markets to publish into</h3>
              <ul>
                <li v-for="market in marketList" :key="market">{{ market }}</li>
              </ul>
            </article>
            <article class="signal-card">
              <h3>Proof stack</h3>
              <ul>
                <li v-for="signal in [...signalList, ...certificateList].slice(0, 6)" :key="signal">{{ signal }}</li>
              </ul>
            </article>
            <article class="signal-card">
              <h3>Top next actions</h3>
              <ul>
                <li>Build one market-specific landing page per priority country.</li>
                <li>Standardize inquiry replies around MOQ, lead time, and proof docs.</li>
                <li>Tag leads by buyer type before quote stage to keep the pipeline clean.</li>
              </ul>
            </article>
          </div>
        </section>
      </template>

      <template v-else-if="activeTab === 'content'">
        <section class="panel content-panel">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Content Studio</p>
              <h2>Generate first-pass export assets from real company inputs</h2>
            </div>
          </div>

          <div class="content-toolbar">
            <label>
              Priority market
              <select v-model="selectedMarket">
                <option value="">Auto pick</option>
                <option v-for="market in marketList" :key="market" :value="market">{{ market }}</option>
              </select>
            </label>
            <label>
              Asset type
              <select v-model="selectedAsset">
                <option v-for="asset in generatedAssets" :key="asset.key" :value="asset.key">{{ asset.title }}</option>
              </select>
            </label>
          </div>

          <div class="content-grid">
            <article class="draft-list">
              <button
                v-for="asset in generatedAssets"
                :key="asset.key"
                class="draft-tile"
                :class="{ 'is-active': selectedAsset === asset.key }"
                type="button"
                @click="selectedAsset = asset.key"
              >
                <strong>{{ asset.title }}</strong>
                <p>{{ asset.summary }}</p>
              </button>
            </article>

            <article class="draft-preview">
              <div class="preview-head">
                <div>
                  <p class="eyebrow">Live Draft</p>
                  <h3>{{ selectedAssetDraft.title }}</h3>
                </div>
                <span class="badge">{{ selectedMarket || marketList[0] || 'Global' }}</span>
              </div>
              <p class="preview-summary">{{ selectedAssetDraft.summary }}</p>
              <pre>{{ selectedAssetDraft.body }}</pre>
            </article>
          </div>
        </section>
      </template>

      <template v-else-if="activeTab === 'leads'">
        <section class="panel lead-entry-panel">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Lead Intake</p>
              <h2>Drop new buyers into the desk and score them immediately</h2>
            </div>
          </div>

          <div class="lead-form-grid">
            <label>
              Company
              <input v-model="leadForm.company" type="text" />
            </label>
            <label>
              Market
              <input v-model="leadForm.market" type="text" />
            </label>
            <label>
              Source
              <input v-model="leadForm.source" type="text" />
            </label>
            <label>
              Contact
              <input v-model="leadForm.contact" type="text" />
            </label>
            <label class="full-span">
              Demand summary
              <textarea v-model="leadForm.demand" rows="3" />
            </label>
            <label>
              Annual volume (USD)
              <input v-model.number="leadForm.annualVolumeUsd" type="number" min="1000" step="1000" />
            </label>
            <label>
              Urgency
              <input v-model.number="leadForm.urgency" type="range" min="1" max="5" />
            </label>
          </div>

          <button class="primary-button" type="button" @click="addLead">Add lead to pipeline</button>
        </section>

        <section class="pipeline-layout">
          <article class="panel top-leads-panel">
            <div class="panel-heading">
              <div>
                <p class="eyebrow">Priority Queue</p>
                <h2>Who deserves the next call</h2>
              </div>
            </div>
            <div class="top-lead-list">
              <article v-for="lead in topLeads" :key="lead.id" class="top-lead-card">
                <div class="top-lead-head">
                  <div>
                    <h3>{{ lead.company }}</h3>
                    <p>{{ lead.market }} · {{ lead.contact }}</p>
                  </div>
                  <span class="priority-pill" :data-priority="leadPriority(lead)">{{ priorityLabel(lead) }}</span>
                </div>
                <p>{{ lead.demand }}</p>
                <dl>
                  <div>
                    <dt>Score</dt>
                    <dd>{{ leadScore(lead) }}</dd>
                  </div>
                  <div>
                    <dt>Value</dt>
                    <dd>${{ lead.annualVolumeUsd.toLocaleString('en-US') }}</dd>
                  </div>
                  <div>
                    <dt>Next step</dt>
                    <dd>{{ lead.nextAction }}</dd>
                  </div>
                </dl>
              </article>
            </div>
          </article>

          <article class="panel board-panel">
            <div class="panel-heading">
              <div>
                <p class="eyebrow">Pipeline Board</p>
                <h2>Move buyers by stage, not by memory</h2>
              </div>
            </div>

            <div class="board-grid">
              <section v-for="group in groupedLeads" :key="group.stage" class="board-column">
                <header>
                  <strong>{{ group.label }}</strong>
                  <span>{{ group.items.length }}</span>
                </header>
                <article v-for="lead in group.items" :key="lead.id" class="lead-card">
                  <div class="lead-card-head">
                    <div>
                      <h3>{{ lead.company }}</h3>
                      <p>{{ lead.market }} · {{ lead.source }}</p>
                    </div>
                    <span class="score-chip">{{ leadScore(lead) }}</span>
                  </div>
                  <p class="lead-demand">{{ lead.demand }}</p>
                  <label>
                    Stage
                    <select :value="lead.stage" @change="updateLeadStage(lead.id, ($event.target as HTMLSelectElement).value as LeadStage)">
                      <option v-for="(label, stage) in stageLabels" :key="stage" :value="stage">{{ label }}</option>
                    </select>
                  </label>
                  <small>{{ lead.nextAction }}</small>
                </article>
              </section>
            </div>
          </article>
        </section>
      </template>

      <template v-else>
        <section class="panel roadmap-panel">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Next Sprint</p>
              <h2>What to build after this MVP lands</h2>
            </div>
          </div>

          <div class="roadmap-grid">
            <article class="roadmap-card">
              <span>1</span>
              <h3>AI-powered content generation</h3>
              <p>Wire the draft templates to an OpenAI-compatible API so replies, pages, and follow-ups can be generated from live product data.</p>
            </article>
            <article class="roadmap-card">
              <span>2</span>
              <h3>Trade CRM sync</h3>
              <p>Push qualified leads into HubSpot, Zoho, or a lightweight internal pipeline with owner assignment and reminders.</p>
            </article>
            <article class="roadmap-card">
              <span>3</span>
              <h3>Export GEO publishing layer</h3>
              <p>Turn asset drafts into structured website pages, FAQ blocks, and distributor-country landing pages for search and AI answer surfaces.</p>
            </article>
            <article class="roadmap-card">
              <span>4</span>
              <h3>Inquiry inbox and SLA monitor</h3>
              <p>Track unanswered inquiries, aging quotes, and sample-stage buyers before the deals quietly cool off.</p>
            </article>
          </div>
        </section>
      </template>
    </main>
  </div>
</template>
