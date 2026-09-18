package com.codecompass.testutil;

import java.util.List;

/**
 * petclinic 问答地面真值（T12 要求 3 的抽样 20 条）。
 *
 * <p>纪律：问题集手写；每条 = 期望锚点类名（可空）+ 期望文件后缀 + 源码文本 marker。
 * 引用由源码快照文本定位行号 —— 独立于解析器与检索层，避免自己证明自己。
 * 桩 LLM 验收与真实 LLM 验收共用同一份清单，保证两个口径测的是同一件事。
 */
public final class PetclinicQaGroundTruth {

    private PetclinicQaGroundTruth() {
    }

    public record Item(String question, String anchorClassName, String fileSuffix, String marker) {
    }

    public static List<Item> items() {
        return List.of(
                new Item("OwnerController 是干什么的", "OwnerController", "OwnerController.java", "class OwnerController"),
                new Item("processFindForm 方法做什么", "OwnerController", "OwnerController.java", "processFindForm"),
                new Item("initFindForm 是做什么的", "OwnerController", "OwnerController.java", "initFindForm"),
                new Item("PetController 的 initCreationForm", "PetController", "PetController.java", "initCreationForm"),
                new Item("processCreationForm 做什么", "PetController", "PetController.java", "processCreationForm"),
                new Item("VetController 如何展示兽医列表", "VetController", "VetController.java", "showVetList"),
                new Item("VisitController 是干什么的", "VisitController", "VisitController.java", "class VisitController"),
                new Item("OwnerRepository 的 findByLastName", "OwnerRepository", "OwnerRepository.java", "findByLastName"),
                new Item("Owner 实体", "Owner", "Owner.java", "class Owner"),
                new Item("Pet 实体有哪些类型", "Pet", "Pet.java", "class Pet"),
                new Item("Visit 实体是什么", null, "Visit.java", "class Visit"),
                new Item("Vet 实体是什么", null, "Vet.java", "class Vet"),
                new Item("Specialty 实体是什么", null, "Specialty.java", "class Specialty"),
                new Item("PetValidator 是干什么的", "PetValidator", "PetValidator.java", "class PetValidator"),
                new Item("PetTypeFormatter 是干什么的", "PetTypeFormatter", "PetTypeFormatter.java", "class PetTypeFormatter"),
                new Item("PetTypeRepository 是做什么的", null, "PetTypeRepository.java", "interface PetTypeRepository"),
                new Item("VetRepository 是做什么的", null, "VetRepository.java", "interface VetRepository"),
                new Item("BaseEntity 是什么", null, "BaseEntity.java", "class BaseEntity"),
                new Item("Person 实体是什么", null, "Person.java", "class Person"),
                new Item("NamedEntity 是什么", null, "NamedEntity.java", "class NamedEntity"));
    }
}
