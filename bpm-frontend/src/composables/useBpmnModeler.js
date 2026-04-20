import { ref, shallowRef } from 'vue'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import { BpmnPropertiesPanelModule, BpmnPropertiesProviderModule } from 'bpmn-js-properties-panel'
import flowableModdle from './flowableModdle.js'

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
  const selectedElement = ref(null)

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
      moddleExtensions: { flowable: flowableModdle }
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
    on, destroy, EMPTY_BPMN
  }
}
