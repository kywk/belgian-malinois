import { shallowRef } from 'vue'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import { BpmnPropertiesPanelModule, BpmnPropertiesProviderModule } from 'bpmn-js-properties-panel'
import flowableModdle, { uniformModdle } from './flowableModdle.js'

const EMPTY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  targetNamespace="http://bpm.com">
  <process id="Process_1" isExecutable="true">
    <startEvent id="StartEvent_1"/>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="_BPMNShape_StartEvent_1" bpmnElement="StartEvent_1">
        <dc:Bounds x="180" y="160" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`

export function useBpmnModeler() {
  let _modeler = null
  const selectedElement = shallowRef(null)

  function createModeler(container, propertiesPanelContainer, additionalModules = []) {
    if (_modeler) _modeler.destroy()

    const modules = [
      BpmnPropertiesPanelModule,
      BpmnPropertiesProviderModule,
      ...additionalModules
    ]

    _modeler = new BpmnModeler({
      container,
      propertiesPanel: { parent: propertiesPanelContainer },
      additionalModules: modules,
      // 兩個 package 都必須註冊：uniform:access（ADR-23）沒有自己的 descriptor
      // 就解析不了 —— 設計器「開啟 → 存檔」會把整段欄位權限吃掉（見
      // flowableModdle.js 檔頭與 uniform 區塊的註解）。
      moddleExtensions: { flowable: flowableModdle, uniform: uniformModdle }
    })

    _modeler.on('selection.changed', (e) => {
      selectedElement.value = e.newSelection?.[0] || null
    })

    return _modeler
  }

  async function importXml(xml) {
    if (!_modeler) return
    await _modeler.importXML(xml || EMPTY_BPMN)
    _modeler.get('canvas').zoom('fit-viewport')
  }

  async function exportXml() {
    if (!_modeler) return ''
    const { xml } = await _modeler.saveXML({ format: true })
    return xml
  }

  function getElement(id) {
    if (!_modeler) return null
    return _modeler.get('elementRegistry').get(id)
  }

  function getSelectedElement() {
    return selectedElement.value
  }

  function getModeling() {
    return _modeler?.get('modeling')
  }

  // 只讓宿主綁定流程層的起始事件；子流程 StartEvent 不是申請入口。
  function getApplicationStartEvents() {
    return _modeler?.get('elementRegistry').filter(element => element.type === 'bpmn:StartEvent'
      && element.businessObject.$parent?.$type === 'bpmn:Process'
      && !element.businessObject.eventDefinitions?.length) ?? []
  }

  function selectElement(id) {
    const element = getElement(id)
    if (element) _modeler.get('selection').select(element)
  }

  function bindStartForm(id, formKey) {
    const element = getApplicationStartEvents().find(start => start.id === id)
    if (!element) throw new Error('請先選取流程層的申請起始事件')
    if (element.businessObject.get('flowable:formKey') === formKey) return
    getModeling().updateProperties(element, { 'flowable:formKey': formKey })
  }

  function on(event, callback) {
    _modeler?.on(event, callback)
  }

  function destroy() {
    _modeler?.destroy()
    _modeler = null
  }

  function newDiagram() {
    return importXml(EMPTY_BPMN)
  }

  return {
    get modeler() { return _modeler },
    selectedElement,
    createModeler, importXml, exportXml, newDiagram,
    getElement, getSelectedElement, getModeling,
    getApplicationStartEvents, selectElement, bindStartForm,
    on, destroy, EMPTY_BPMN
  }
}
