package cn.wolfcode.car.business.service.impl;

import cn.wolfcode.car.business.domain.BpmnInfo;
import cn.wolfcode.car.business.domain.CarPackageAudit;
import cn.wolfcode.car.business.domain.ServiceItem;
import cn.wolfcode.car.business.mapper.CarPackageAuditMapper;
import cn.wolfcode.car.business.query.CarPackageAuditQuery;
import cn.wolfcode.car.business.service.IBpmnInfoService;
import cn.wolfcode.car.business.service.ICarPackageAuditService;
import cn.wolfcode.car.business.service.IServiceItemService;
import cn.wolfcode.car.common.base.page.TablePageInfo;
import cn.wolfcode.car.common.exception.BusinessException;
import cn.wolfcode.car.common.util.Convert;
import cn.wolfcode.car.shiro.ShiroUtils;
import com.github.pagehelper.PageHelper;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.task.Task;
import org.activiti.image.impl.DefaultProcessDiagramGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
@Transactional
public class CarPackageAuditImpl implements ICarPackageAuditService {

    @Autowired
    private CarPackageAuditMapper carPackageAuditMapper;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private IBpmnInfoService bpmnInfoService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private IServiceItemService serviceItemService;
    @Autowired
    private TaskService taskService;

    @Override
    public TablePageInfo<CarPackageAudit> query(CarPackageAuditQuery qo) {
        PageHelper.startPage(qo.getPageNum(), qo.getPageSize());
        return new TablePageInfo<CarPackageAudit>(carPackageAuditMapper.selectForList(qo));
    }

    @Override
    public void save(CarPackageAudit carPackageAudit) {
        carPackageAudit.setCreateTime(new Date());
        carPackageAuditMapper.insert(carPackageAudit);
    }

    @Override
    public CarPackageAudit get(Long id) {
        return carPackageAuditMapper.selectByPrimaryKey(id);
    }


    @Override
    public void update(CarPackageAudit carPackageAudit) {
        carPackageAuditMapper.updateByPrimaryKey(carPackageAudit);
    }

    @Override
    public void deleteBatch(String ids) {
        Long[] dictIds = Convert.toLongArray(ids);
        for (Long dictId : dictIds) {
            carPackageAuditMapper.deleteByPrimaryKey(dictId);
        }
    }

    @Override
    public List<CarPackageAudit> list() {
        return carPackageAuditMapper.selectAll();
    }

    @Override
    public InputStream getProcessImgAsStream(Long id) {
        //??????id?????????????????? ???????????????????????????
        CarPackageAudit audit = this.get(id);
        String instanceId = audit.getInstanceId();//??????????????????????????????id
        List<String> highLightedActivities = null;
        if (CarPackageAudit.STATUS_IN_ROGRESS.equals(audit.getStatus())) {
            //????????????????????????
            //????????????????????? ??????????????????????????????
            //?????????????????????
            highLightedActivities = runtimeService.getActiveActivityIds(instanceId);//??????????????????ID???????????????????????????
        } else {
            //????????????????????? ???????????? ???????????????????????????
            highLightedActivities = new ArrayList<>();
        }
        //??????bpmnInfoId??????bpmnInfo??????u?????? ???????????????????????????id
        BpmnInfo bpmnInfo = bpmnInfoService.get(audit.getBpmnInfoId());
        BpmnModel model = repositoryService.getBpmnModel(bpmnInfo.getActProcessId());
        DefaultProcessDiagramGenerator generator = new DefaultProcessDiagramGenerator();
        return generator.generateDiagram(model, highLightedActivities, Collections.EMPTY_LIST, "??????", "??????", "??????");
    }

    @Override
    @Transactional
    public void cancelApply(Long id) {
        //???????????????
        CarPackageAudit audit = this.get(id);
        if (!CarPackageAudit.STATUS_IN_ROGRESS.equals(audit.getStatus())) {
            throw new BusinessException("?????????????????????????????????????????????");
        }
        //1.???????????????????????????????????????
        Long serviceItemId = audit.getServiceItemId();
        serviceItemService.changeAuditStatus(serviceItemId, ServiceItem.AUDITSTATUS_INIT);
        //2.?????????????????????
        runtimeService.deleteProcessInstance(audit.getInstanceId(), "????????????");
        //3.????????????????????????????????????
        carPackageAuditMapper.changeStatus(id, CarPackageAudit.STATUS_CANCEL);
    }

    @Override
    public void audit(Long id, Integer auditStatus, String info) {
        //???????????????
        CarPackageAudit audit = this.get(id);
        //??????????????????????????????????????????
        if (!(CarPackageAudit.STATUS_IN_ROGRESS.equals(audit.getStatus()))) {
            throw new BusinessException("????????????");
        }
        //???????????????????????????????????????????????????
        if (!ShiroUtils.getUserId().equals(audit.getAuditorId())) {
            throw new BusinessException("????????????");
        }
        String username = ShiroUtils.getUser().getUserName();
        if (CarPackageAudit.STATUS_PASS.equals(auditStatus)) {
            //????????????
            info = "[" + username + "]??????,????????????:" + info;
        } else {
            //????????????
            info = "[" + username + "]??????,????????????:" + info;
        }
        //??????auditStatus???????????????????????? ?????????info???????????????????????????????????????
        audit.setInfo(audit.getInfo() + "</br>" + info);
        //???Activiti????????????????????????
        Task currentTask = taskService.createTaskQuery().processInstanceId(audit.getInstanceId()).singleResult();
        //????????????????????????????????? auditStatus
        taskService.setVariable(currentTask.getId(),"auditStatus",auditStatus);
        //????????????
        taskService.complete(currentTask.getId());
        //??????????????????
        audit.setAuditTime(new Date());
        //???????????????????????????????????????????????????
        if (CarPackageAudit.STATUS_PASS.equals(auditStatus)) {
            //???????????????
            //??????????????????????????????
            Task nextTask = taskService.createTaskQuery().processInstanceId(audit.getInstanceId()).singleResult();
            if (nextTask == null) {
                //??????????????? ???auditorId????????????
                audit.setAuditorId(null);
                //??????????????????????????? ??????????????? ??????????????????????????? ????????????????????????????????? ?????????????????????????????????
                audit.setStatus(CarPackageAudit.STATUS_PASS);
                serviceItemService.changeAuditStatus(audit.getServiceItemId(), ServiceItem.AUDITSTATUS_APPROVED);
            } else {
                //???????????????????????? ????????????????????????????????????Id ????????????????????????????????????
                audit.setAuditorId(Long.parseLong(nextTask.getAssignee()));
            }
        } else {
            //???????????????
            //??????????????? ???auditorId????????????
            audit.setAuditorId(null);
            //??????????????????????????????????????????
            audit.setStatus(CarPackageAudit.STATUS_REJECT);
            //??????????????????????????? ????????????
            serviceItemService.changeAuditStatus(audit.getServiceItemId(), ServiceItem.AUDITSTATUS_REPLY);
        }
        //????????????????????????
        carPackageAuditMapper.updateByPrimaryKey(audit);
    }
}

