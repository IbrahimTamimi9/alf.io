/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.model.Audit;
import alfio.model.Ticket;
import alfio.model.group.Group;
import alfio.model.group.GroupMember;
import alfio.model.group.LinkedGroup;
import alfio.model.modification.GroupMemberModification;
import alfio.model.modification.GroupModification;
import alfio.model.modification.LinkedGroupModification;
import alfio.repository.AuditingRepository;
import alfio.repository.GroupRepository;
import alfio.repository.TicketRepository;
import ch.digitalfondue.npjt.AffectedRowCountAndKey;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static alfio.model.group.LinkedGroup.MatchType.FULL;
import static alfio.model.group.LinkedGroup.Type.*;
import static java.util.Collections.singletonList;

@AllArgsConstructor
@Transactional
@Component
@Log4j2
public class GroupManager {

    private final GroupRepository groupRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TicketRepository ticketRepository;
    private final AuditingRepository auditingRepository;

    public int createNew(GroupModification input) {
        Group wl = createNew(input.getName(), input.getDescription(), input.getOrganizationId());
        insertMembers(wl.getId(), input.getItems());
        return wl.getId();
    }

    public Group createNew(String name, String description, int organizationId) {
        AffectedRowCountAndKey<Integer> insert = groupRepository.insert(name, description, organizationId);
        return groupRepository.getById(insert.getKey());
    }

    public LinkedGroup createLink(int groupId,
                                  int eventId,
                                  LinkedGroupModification modification) {
        Objects.requireNonNull(groupRepository.getById(groupId), "Group not found");
        Validate.isTrue(modification.getType() != LIMITED_QUANTITY || modification.getMaxAllocation() != null, "Missing max allocation");
        AffectedRowCountAndKey<Integer> configuration = groupRepository.createConfiguration(groupId, eventId,
            modification.getTicketCategoryId(), modification.getType(), modification.getMatchType(), modification.getMaxAllocation());
        return groupRepository.getConfiguration(configuration.getKey());
    }

    public LinkedGroup updateLink(int id, LinkedGroupModification modification) {
        LinkedGroup original = groupRepository.getConfigurationForUpdate(id);
        if(requiresCleanState(modification, original)) {
            Validate.isTrue(groupRepository.countWhitelistedTicketsForConfiguration(original.getId()) == 0, "Cannot update as there are already confirmed tickets.");
        }
        groupRepository.updateConfiguration(id, modification.getGroupId(), original.getEventId(), modification.getTicketCategoryId(), modification.getType(), modification.getMatchType(), modification.getMaxAllocation());
        return groupRepository.getConfiguration(id);
    }

    private boolean requiresCleanState(LinkedGroupModification modification, LinkedGroup original) {
        return (original.getType() == UNLIMITED && modification.getType() != UNLIMITED)
            || original.getGroupId() != modification.getGroupId()
            || (modification.getType() == LIMITED_QUANTITY && modification.getMaxAllocation() != null && original.getMaxAllocation() != null && modification.getMaxAllocation().compareTo(original.getMaxAllocation()) < 0);
    }

    public boolean isGroupLinked(int eventId, int categoryId) {
        return CollectionUtils.isNotEmpty(findLinks(eventId, categoryId));
    }

    public List<Group> getAllForOrganization(int organizationId) {
        return groupRepository.getAllForOrganization(organizationId);
    }

    public Optional<GroupModification> loadComplete(int id) {
        return groupRepository.getOptionalById(id)
            .map(wl -> {
                List<GroupMemberModification> items = groupRepository.getItems(wl.getId()).stream().map(i -> new GroupMemberModification(i.getId(), i.getValue(), i.getDescription())).collect(Collectors.toList());
                return new GroupModification(wl.getId(), wl.getName(), wl.getDescription(), wl.getOrganizationId(), items);
            });
    }

    public Optional<Group> findById(int groupId, int organizationId) {
        return groupRepository.getOptionalById(groupId).filter(w -> w.getOrganizationId() == organizationId);
    }

    public boolean isAllowed(String value, int eventId, int categoryId) {

        List<LinkedGroup> configurations = findLinks(eventId, categoryId);
        if(CollectionUtils.isEmpty(configurations)) {
            return true;
        }
        LinkedGroup configuration = configurations.get(0);
        return getMatchingMember(configuration, value).isPresent();
    }

    public List<LinkedGroup> getLinksForEvent(int eventId) {
        return groupRepository.findActiveConfigurationsForEvent(eventId);
    }

    public List<LinkedGroup> findLinks(int eventId, int categoryId) {
        return groupRepository.findActiveConfigurationsFor(eventId, categoryId);
    }

    public int insertMembers(int groupId, List<GroupMemberModification> members) {
        MapSqlParameterSource[] params = members.stream()
            .map(i -> new MapSqlParameterSource("groupId", groupId).addValue("value", i.getValue()).addValue("description", i.getDescription()))
            .toArray(MapSqlParameterSource[]::new);
        return Arrays.stream(jdbcTemplate.batchUpdate(groupRepository.insertItemTemplate(), params)).sum();
    }

    boolean acquireMemberForTicket(Ticket ticket, String email) {
        List<LinkedGroup> configurations = findLinks(ticket.getEventId(), ticket.getCategoryId());
        if(CollectionUtils.isEmpty(configurations)) {
            return true;
        }
        LinkedGroup configuration = configurations.get(0);
        Optional<GroupMember> optionalItem = getMatchingMember(configuration, StringUtils.defaultString(StringUtils.trimToNull(ticket.getEmail()), email));
        if(!optionalItem.isPresent()) {
            return false;
        }
        GroupMember item = optionalItem.get();
        boolean preventDuplication = configuration.getType() == ONCE_PER_VALUE;
        boolean limitAssignments = preventDuplication || configuration.getType() == LIMITED_QUANTITY;
        if(limitAssignments) {
            //reload and lock configuration
            configuration = groupRepository.getConfigurationForUpdate(configuration.getId());
            int existing = groupRepository.countExistingWhitelistedTickets(item.getId(), configuration.getId());
            int expected = preventDuplication ? 1 : Optional.ofNullable(configuration.getMaxAllocation()).orElse(0);
            if(existing >= expected) {
                return false;
            }
        }
        groupRepository.insertWhitelistedTicket(item.getId(), configuration.getId(), ticket.getId(), preventDuplication ? true : null);
        Map<String, Object> modifications = new HashMap<>();
        modifications.put("itemId", item.getId());
        modifications.put("configurationId", configuration.getId());
        modifications.put("ticketId", ticket.getId());
        auditingRepository.insert(ticket.getTicketsReservationId(), null, ticket.getEventId(), Audit.EventType.GROUP_MEMBER_ACQUIRED, new Date(), Audit.EntityType.TICKET, ticket.getUuid(), singletonList(modifications));
        return true;
    }

    private Optional<GroupMember> getMatchingMember(LinkedGroup configuration, String email) {
        String trimmed = StringUtils.trimToEmpty(email);
        Optional<GroupMember> exactMatch = groupRepository.findItemByValueExactMatch(configuration.getGroupId(), trimmed);
        if(exactMatch.isPresent() || configuration.getMatchType() == FULL) {
            return exactMatch;
        }
        String partial = StringUtils.substringAfterLast(trimmed, "@");
        return partial.length() > 0 ? groupRepository.findItemEndsWith(configuration.getId(), configuration.getGroupId(), "%@"+partial) : Optional.empty();
    }

    public void deleteWhitelistedTicketsForReservation(String reservationId) {
        List<Integer> tickets = ticketRepository.findTicketsInReservation(reservationId).stream().map(Ticket::getId).collect(Collectors.toList());
        if(!tickets.isEmpty()) {
            int result = groupRepository.deleteExistingWhitelistedTickets(tickets);
            log.trace("deleted {} whitelisted tickets for reservation {}", result, reservationId);
        }
    }

    public void disableLink(int linkId) {
        Validate.isTrue(groupRepository.disableLink(linkId) == 1, "Error while disabling link");
    }

    public Optional<GroupModification> update(int listId, GroupModification modification) {

        if(!groupRepository.getOptionalById(listId).isPresent() || CollectionUtils.isEmpty(modification.getItems())) {
            return Optional.empty();
        }

        List<GroupMember> existingItems = groupRepository.getItems(listId);
        List<GroupMemberModification> notPresent = modification.getItems().stream()
            .filter(i -> i.getId() == null && existingItems.stream().noneMatch(ali -> ali.getValue().equals(i.getValue())))
            .collect(Collectors.toList());
        if(!notPresent.isEmpty()) {
            insertMembers(listId, notPresent);
        }
        groupRepository.update(listId, modification.getName(), modification.getDescription());
        return loadComplete(listId);
    }

    public boolean deactivateMembers(List<Integer> memberIds, int groupId) {
        if(memberIds.isEmpty()) {
            return false;
        }
        MapSqlParameterSource[] params = memberIds.stream().map(i -> toParameterSource(groupId, i)).toArray(MapSqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(groupRepository.deactivateGroupMember(), params);
        return true;
    }

    public boolean deactivateGroup(int groupId) {
        List<Integer> members = groupRepository.getItems(groupId).stream().map(GroupMember::getId).collect(Collectors.toList());
        if(!members.isEmpty()) {
            Validate.isTrue(deactivateMembers(members, groupId), "error while disabling group members");
        }
        groupRepository.disableAllLinks(groupId);
        Validate.isTrue(groupRepository.deactivateGroup(groupId) == 1, "unexpected error while disabling group");
        return true;
    }


    private static MapSqlParameterSource toParameterSource(int groupId, Integer itemId) {
        return new MapSqlParameterSource("groupId", groupId)
            .addValue("memberId", itemId)
            .addValue("disabledPlaceholder", UUID.randomUUID().toString());
    }

    @RequiredArgsConstructor
    public static class WhitelistValidator implements Predicate<WhitelistValidationItem> {

        private final int eventId;
        private final GroupManager groupManager;

        @Override
        public boolean test(WhitelistValidationItem item) {
            return groupManager.isAllowed(item.value, eventId, item.categoryId);
        }
    }

    @RequiredArgsConstructor
    public static class WhitelistValidationItem {
        private final int categoryId;
        private final String value;
    }
}
