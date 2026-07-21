import { createRouter, createWebHistory } from 'vue-router'
import MerchantWorkspaceLayout from '../layouts/MerchantWorkspaceLayout.vue'
import Overview from '../modules/overview/Overview.vue'
import Facts from '../modules/facts/Facts.vue'
import PlatformAssets from '../modules/platform-assets/PlatformAssets.vue'
import QuestionsObservations from '../modules/questions-observations/QuestionsObservations.vue'
import ObservationAutomation from '../modules/observation-automation/ObservationAutomation.vue'
import RetestAutomation from '../modules/retest-automation/RetestAutomation.vue'
import DiagnosisStrategy from '../modules/diagnosis-strategy/DiagnosisStrategy.vue'
import Execution from '../modules/execution/Execution.vue'
import RetestsReports from '../modules/retests-reports/RetestsReports.vue'
import Playbooks from '../modules/playbooks/Playbooks.vue'
import ContentExecutionCenter from '../modules/content-execution/ContentExecutionCenter.vue'
const routes=[{path:'/merchants/:merchantId',component:MerchantWorkspaceLayout,redirect:(to:any)=>`/merchants/${to.params.merchantId}/overview`,children:[
 {path:'overview',component:Overview},{path:'facts',component:Facts},{path:'platform-assets',component:PlatformAssets},{path:'questions-observations',component:QuestionsObservations},{path:'observation-automation',component:ObservationAutomation},{path:'retest-automation',component:RetestAutomation},{path:'diagnosis-strategy',component:DiagnosisStrategy},{path:'execution',component:Execution},{path:'content-execution',component:ContentExecutionCenter},{path:'retests-reports',component:RetestsReports},{path:'playbooks',component:Playbooks}
]}]
export default createRouter({history:createWebHistory(),routes})
