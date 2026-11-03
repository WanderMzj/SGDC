# Semantic Analysis of Discovered Denial Constraints Across Datasets

---

## 1. Airports Dataset (8 DCs)
*Domain: Global airport facilities and aviation administrative coding*

### Strong Semantics (6 DCs)

| ID      | Denial Constraint Logic                                      | Semantic Interpretation                                      |
| ------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC8** | `¬(t0.ident = t1.ident)`                                     | **Primary Key Constraint (ICAO Standard)**. The ICAO airport identifier (e.g., "KJFK", "EGLL") serves as the global unique entity key for aviation facilities. |
| **DC4** | `¬(t0.iso_country ≠ t1.iso_country ^ t0.iso_region = t1.iso_region)` | **ISO Region-to-Country Hierarchy**. ISO region codes (e.g., "US-CA", "GB-ENG") structurally embed the country prefix, enforcing administrative containment. |
| **DC6** | `¬(t0.coordinates = t1.coordinates ^ t0.iso_country ≠ t1.iso_country)` | **Geographic Sovereignty Principle**. Identical coordinates (latitude/longitude) cannot span different sovereign nations, reflecting geospatial integrity. |
| **DC7** | `¬(t0.coordinates = t1.coordinates ^ t0.continent ≠ t1.continent)` | **Continental Assignment Axiom**. Geographic coordinates uniquely determine continental classification based on international geodesic standards. |
| **DC1** | `¬(t0.coordinates = t1.coordinates ^ t0.type ≠ t1.type ^ t0.iso_region ≠ t1.iso_region)` | **Coordinated Facility Jurisdiction Rule (Disjunctive)**. Facilities sharing precise coordinates (e.g., co-located civil/military airports or adjacent navigation aids) must either belong to the same **airspace administration region** (`iso_region`) or share the same **facility type** to avoid cross-jurisdictional airspace conflicts. Captures the "coordination-by-region" principle in aviation management. |
| **DC5** | `¬(t0.coordinates = t1.coordinates ^ t0.gps_code ≠ t1.gps_code ^ t0.iso_region ≠ t1.iso_region)` | **GPS Point Regional Allocation Rule (Disjunctive)**. When distinct GPS waypoints or airport identifiers (`gps_code`) share identical coordinates (e.g., multiple runway thresholds or helipads at a complex), they must be administered by the **same regional aviation authority** (`iso_region`). Reflects the **regional exclusivity** of GPS code assignment within coordinated airspace. |

### Weak Semantics (2 DCs)

| ID      | Denial Constraint Logic                                      | Explanation                                                  |
| ------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC2** | `¬(t0.coordinates = t1.coordinates ^ t0.iso_region ≠ t1.iso_region ^ t0.local_code ≠ t1.local_code)` | **Administrative Coding Coincidence**. While `local_code` (municipal/local aviation codes) often correlate with regions, there is no aviation standard mandating that co-located facilities must share either region or local code. This is a **dataset-specific statistical pattern** arising from localized data collection practices rather than a generalizable airspace rule. |
| **DC3** | `¬(t0.coordinates = t1.coordinates ^ t0.iso_region ≠ t1.iso_region ^ t0.elevation_ft ≠ t1.elevation_ft)` | **Elevation-Region Statistical Artifact**. Elevation (`elevation_ft`) is a physical measurement independent of administrative regions. The constraint holds due to **data flattening** (specific elevation values coinciding with regional assignments in the sampled subset), not due to any topographic or administrative principle. |

---

## 2. FODD (FoodData Central) Dataset (16 DCs)
*Domain: USDA branded food products and nutritional data*

### Strong Semantics (11 DCs)

| ID       | Denial Constraint Logic                                      | Semantic Interpretation                                      |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC14** | `¬(t0.fdc_id = t1.fdc_id)`                                   | **Primary Key**. The FDC ID uniquely identifies food items in the USDA database. |
| **DC16** | `¬(t0.data_source ≠ t1.data_source ^ t0.branded_food_category = t1.branded_food_category)` | **Category-Specific Data Sourcing Protocol**. Certain food categories (e.g., "Baby Foods") are sourced exclusively from specific channels (e.g., FDA-mandated submissions), reflecting USDA's multi-source integration strategy. |
| **DC12** | `¬(t0.brand_name ≠ t1.brand_name ^ t0.ingredients = t1.ingredients)` | **Recipe Fingerprint Hypothesis**. Identical ingredient sequences (chemical fingerprints) strongly correlate with brand ownership, useful for detecting OEM co-packing or label copying. |
| **DC13** | `¬(t0.package_weight ≠ t1.package_weight ^ t0.ingredients = t1.ingredients)` | **Standardized Packaging Convention**. Specific formulations are typically produced in fixed standard sizes (e.g., "250g glass bottle only"), reflecting manufacturing standardization. |
| **DC1**  | `¬(t0.brand_name ≠ t1.brand_name ^ t0.package_weight = t1.package_weight)` | **Weight Exclusivity (Signature Packaging)**. Certain package sizes serve as signature identifiers for brands within this dataset, useful for detecting counterfeit products. |
| **DC4**  | `¬(t0.available_date = t1.available_date ^ t0.package_weight ≠ t1.package_weight)` | **Batch Release Consistency**. Data maintenance occurs by weight-based batches, where products of identical weight are processed in the same release window. |
| **DC5**  | `¬(t0.package_weight ≠ t1.package_weight ^ t0.modified_date = t1.modified_date)` | **Maintenance Batch Uniformity**. Backend data updates are often synchronized by package specification during USDA data refreshes. |
| **DC6**  | `¬(t0.brand_owner = t1.brand_owner ^ t0.package_weight ≠ t1.package_weight)` | **Portfolio Strategy Concentration**. Some brand owners (e.g., boutique organic companies) adopt single-size focus strategies for brand identity, captured as a dataset-specific pattern. |
| **DC7**  | `¬(t0.brand_owner = t1.brand_owner ^ t0.brand_name ≠ t1.brand_name)` | **Single-Brand Focus (Sampled)**. Reflects dataset focus on flagship brands; while conglomerates own multiple brands, the sampled subset shows owner-brand alignment. |
| **DC9**  | `¬(t0.brand_name ≠ t1.brand_name ^ t0.modified_date = t1.modified_date)` | **Brand-Level Bulk Updates**. Data sharing agreement renewals trigger synchronized modification timestamps across a brand's entire product line. |
| **DC10** | `¬(t0.available_date = t1.available_date ^ t0.brand_name ≠ t1.brand_name)` | **Release Event Coupling**. Brand-level data publication events create temporal clustering in availability dates. |
| **DC11** | `¬(t0.available_date ≠ t1.available_date ^ t0.modified_date = t1.modified_date)` | **ETL Backfill Consistency**. System-level date linkages during data ingestion, where modified dates trigger available date standardization. |

### Weak Semantics (5 DCs)

| ID       | Denial Constraint Logic                                      | Explanation                                                  |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC2**  | `¬(t0.brand_name = t1.brand_name ^ t0.package_weight ≠ t1.package_weight)` | **Sampling Bias (Single-Size Artifacts)**. Violates commercial reality (brands offer multiple sizes); holds only because the dataset sampled "standard retail sizes" exclusively. |
| **DC3**  | `¬(t0.market_country ≠ t1.market_country)`                   | **Dataset Scope Limitation**. All records are from the US (United States), reflecting USDA FDC's scope rather than a geographic rule. |
| **DC8**  | `¬(t0.not_a_significant_source_of ≠ t1.not_a_significant_source_of)` | **Static Template Field**. Contains a constant FDA disclaimer template (e.g., "Not a significant source of added sugars") across all records, an ETL default value rather than a constraint. |
| **DC15** | `¬(t0.subbrand_name ≠ t1.subbrand_name)`                     | **Data Slice Characteristic**. Subbrand field is uniformly populated with default/null values because the dataset focuses on main product lines only. |

---

## 3. Hospital Compare Dataset (25 DCs)
*Domain: CMS Hospital Quality Metrics and Healthcare Facility Administration*

### Strong Semantics (20 DCs)

| ID       | Denial Constraint Logic                                      | Semantic Interpretation                                      |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC1**  | `¬(t0.City ≠ t1.City ^ t0.Provider Number = t1.Provider Number)` | **Hospital Location Stability**. CMS Certification Number (Provider Number) determines the city location of the facility. |
| **DC4**  | `¬(t0.Phone Number ≠ t1.Phone Number ^ t0.Provider Number = t1.Provider Number)` | **Entity Contact Consistency**. Hospitals maintain primary contact numbers tied to their CMS identifier. |
| **DC5**  | `¬(t0.Emergency Service ≠ t1.Emergency Service ^ t0.Provider Number = t1.Provider Number)` | **Service Profile Stability**. Emergency service availability is a static facility attribute tied to the hospital entity. |
| **DC6**  | `¬(t0.State ≠ t1.State ^ t0.Provider Number = t1.Provider Number)` | **State Jurisdiction**. Provider Number determines the state location (administrative boundary). |
| **DC8**  | `¬(t0.Hospital Type ≠ t1.Hospital Type ^ t0.Provider Number = t1.Provider Number)` | **Type Classification**. Hospital type (Acute Care, Critical Access, etc.) is an intrinsic property fixed at certification. |
| **DC9**  | `¬(t0.Hospital Owner ≠ t1.Hospital Owner ^ t0.Provider Number = t1.Provider Number)` | **Ownership Structure**. Proprietary/Voluntary/Government ownership is static for the certified entity. |
| **DC16** | `¬(t0.ZIP Code = t1.ZIP Code ^ t0.City ≠ t1.City)`           | **USPS Geographic Standard**. ZIP Code uniquely determines the delivery city (postal administrative rule). |
| **DC18** | `¬(t0.County Name ≠ t1.County Name ^ t0.Provider Number = t1.Provider Number)` | **County Assignment**. Hospital location determines county jurisdiction for public health reporting. |
| **DC24** | `¬(t0.ZIP Code = t1.ZIP Code ^ t0.State ≠ t1.State)`         | **ZIP-State Hierarchy**. ZIP Code belongs to exactly one state (USPS standard). |
| **DC25** | `¬(t0.ZIP Code ≠ t1.ZIP Code ^ t0.Provider Number = t1.Provider Number)` | **Hospital Address Stability**. Provider Number determines the fixed ZIP Code of the hospital's location. |
| **DC3**  | `¬(t0.State ≠ t1.State ^ t0.Phone Number = t1.Phone Number)` | **NANP Area Code Geography**. Telephone area codes map to states (North American Numbering Plan standard). |
| **DC7**  | `¬(t0.City ≠ t1.City ^ t0.Phone Number = t1.Phone Number)`   | **Telephone Exchange Localization**. Area code + prefix determines city (telecom infrastructure standard). |
| **DC22** | `¬(t0.ZIP Code ≠ t1.ZIP Code ^ t0.Phone Number = t1.Phone Number)` | **Local Exchange-Postcode Coupling**. Phone exchanges and ZIP codes align at the local geography level. |
| **DC11** | `¬(t0.Condition ≠ t1.Condition ^ t0.Measure Code = t1.Measure Code)` | **Clinical Ontology Mapping**. CMS Clinical Quality Measures encode the disease condition (e.g., Heart Failure) in their code structure. |
| **DC19** | `¬(t0.Condition ≠ t1.Condition ^ t0.Measure Name = t1.Measure Name)` | **Taxonomic Naming Convention**. Measure names embed condition categories through standardized nomenclature. |
| **DC20** | `¬(t0.Measure Code = t1.Measure Code ^ t0.Measure Name ≠ t1.Measure Name)` | **Canonical Naming Standard**. CMS enforces standardized measure names for each code to ensure cross-state comparability. |
| **DC2**  | `¬(t0.Provider Number = t1.Provider Number ^ t0.Measure Name = t1.Measure Name)` | **Measure-Level Granularity**. Composite key ensuring each hospital reports exactly one value per clinical measure in the cross-sectional dataset. |
| **DC14** | `¬(t0.Measure Code = t1.Measure Code ^ t0.Provider Number = t1.Provider Number)` | **Record Uniqueness**. Ensures no duplicate reporting for the same provider-measure combination. |
| **DC17** | `¬(t0.Provider Number = t1.Provider Number ^ t0.Hospital Name ≠ t1.Hospital Name)` | **Legal Name Stability**. While marketing names change, the legal entity name associated with Provider Number remains consistent for longitudinal tracking. |
| **DC21** | `¬(t0.Measure Code = t1.Measure Code ^ t0.Measure Name ≠ t1.Measure Name)` | **Bidirectional Mapping (Snapshot Validity)**. In the current schema version, codes and names are 1:1, ensuring identifier consistency. |

### Weak Semantics (5 DCs)

| ID       | Denial Constraint Logic                                      | Explanation                                                  |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC10** | `¬(t0.StateAvg = t1.StateAvg ^ t0.State ≠ t1.State)`         | **Statistical Coincidence (Precision Artifact)**. State averages appear unique per state due to limited decimal precision and small measure subset, not policy. Different states having different averages reflects sampling, not a constraint that "averages must differ." |
| **DC12** | `¬(t0.Condition ≠ t1.Condition ^ t0.StateAvg = t1.StateAvg)` | **Aggregate Value Collision**. Different conditions having different state averages is coincidental; no medical rule links aggregates to disease categories. |
| **DC13** | `¬(t0.Provider Number = t1.Provider Number ^ t0.StateAvg = t1.StateAvg)` | **Single-Measure Subset Limitation**. Suggests one state average per hospital, revealing the dataset contains only one measure type (e.g., only Heart Failure metrics) or is pre-aggregated by measure. |
| **DC15** | `¬(t0.Measure Code = t1.Measure Code ^ t0.StateAvg = t1.StateAvg)` | **Measure-Specific Average Uniqueness**. Different measures having different state averages is a numerical coincidence in this slice, not a clinical rule. |
| **DC23** | `¬(t0.StateAvg = t1.StateAvg ^ t0.Measure Name ≠ t1.Measure Name)` | **Mean Value String Determinism**. State average values coincidentally map to unique measure names in this specific data slice, lacking generalizable semantic meaning. |

## 4. Tax500k Dataset (7 DCs)
*Domain: IRS Tax Return Statistics (Individual Taxpayer Data)*

### Strong Semantics (4 DCs)

| ID      | Denial Constraint Logic                                      | Semantic Interpretation                                      |
| ------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC1** | `¬(t0.State ≠ t1.State ^ t0.AreaCode = t1.AreaCode)`         | **NANP Geographic Rigidity**. Telephone area codes are geographically assigned to states (e.g., 212→NY, 310→CA). |
| **DC2** | `¬(t0.Zip = t1.Zip ^ t0.City ≠ t1.City)`                     | **USPS Postal Standard**. ZIP Code uniquely determines the delivery city. |
| **DC3** | `¬(t0.State ≠ t1.State ^ t0.Zip = t1.Zip)`                   | **ZIP-State Affiliation**. ZIP Codes belong to exactly one state (USPS administrative boundary). |
| **DC4** | `¬(t0.MarriedExemp ≠ t1.MarriedExemp ^ t0.MaritalStatus = t1.MaritalStatus ^ t0.SingleExemp ≠ t1.SingleExemp)` | **Filing Consistency Rule (Tax Year Specific)**. For a given marital status, standard deduction amounts are fixed by tax law in a specific year. The disjunctive form captures that exemptions align with status. |

### Weak Semantics (3 DCs)

| ID      | Denial Constraint Logic                                      | Explanation                                                  |
| ------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC5** | `¬(t0.FName = t1.FName ^ t0.Gender ≠ t1.Gender)`             | **Gender Imputation Bias**. Assumes first names determine gender (e.g., "John"→Male), ignoring unisex names and non-binary identities. Reflects historical administrative data binary bias, not social reality. |
| **DC6** | `¬(t0.MarriedExemp &lt; t1.MarriedExemp ^ t0.SingleExemp &lt; t1.SingleExemp)` | **False Monotonicity (Data Flattening)**. No tax rule prevents simultaneous increases; holds only because the dataset contains static exemption values for a single tax year, lacking value diversity. |
| **DC7** | `¬(t0.Phone = t1.Phone ^ t0.AreaCode = t1.AreaCode)`         | **Household Sampling Bias**. Treats phone number as unique key, which fails in reality (families share numbers). Indicates head-of-household sampling where only one taxpayer per phone is retained. |

---

## 5. Flights (On-Time Performance) Dataset (66 DCs)

*Domain: BTS Airline On-Time Performance Data*

### Detailed DC List 

| ID       | Denial Constraint                                            |
| -------- | ------------------------------------------------------------ |
| **DC1**  | `¬(t0.OriginCityName(String) <> t1.OriginCityName(String) ^ t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer))` |
| **DC2**  | `¬(t0.Year(Integer) <> t1.Year(Integer))`                    |
| **DC3**  | `¬(t0.OriginAirportID(Integer) <> t1.OriginAirportID(Integer) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC4**  | `¬(t0.Origin(String) <> t1.Origin(String) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC5**  | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginStateName(String) == t1.OriginStateName(String))` |
| **DC6**  | `¬(t0.OriginStateFips(Integer) == t1.OriginStateFips(Integer) ^ t0.OriginStateName(String) <> t1.OriginStateName(String))` |
| **DC7**  | `¬(t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer) ^ t0.OriginAirportID(Integer) <> t1.OriginAirportID(Integer))` |
| **DC8**  | `¬(t0.OriginAirportSeqID(Integer) <> t1.OriginAirportSeqID(Integer) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC9**  | `¬(t0.DayofMonth(Integer) == t1.DayofMonth(Integer) ^ t0.DayOfWeek(Integer) <> t1.DayOfWeek(Integer) ^ t0.Month(Integer) == t1.Month(Integer))` |
| **DC10** | `¬(t0.DayOfWeek(Integer) <> t1.DayOfWeek(Integer) ^ t0.FlightDate(String) == t1.FlightDate(String))` |
| **DC11** | `¬(t0.OriginCityName(String) <> t1.OriginCityName(String) ^ t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer))` |
| **DC12** | `¬(t0.OriginState(String) <> t1.OriginState(String) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC13** | `¬(t0.OriginWac(Integer) == t1.OriginWac(Integer) ^ t0.OriginStateName(String) <> t1.OriginStateName(String))` |
| **DC14** | `¬(t0.OriginStateName(String) == t1.OriginStateName(String) ^ t0.OriginWac(Integer) <> t1.OriginWac(Integer))` |
| **DC15** | `¬(t0.OriginCityMarketID(Integer) <> t1.OriginCityMarketID(Integer) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC16** | `¬(t0.OriginCityMarketID(Integer) <> t1.OriginCityMarketID(Integer) ^ t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer))` |
| **DC17** | `¬(t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer) ^ t0.OriginState(String) <> t1.OriginState(String))` |
| **DC18** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC19** | `¬(t0.OriginCityName(String) <> t1.OriginCityName(String) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC20** | `¬(t0.OriginStateName(String) <> t1.OriginStateName(String) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC21** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer))` |
| **DC22** | `¬(t0.Origin(String) <> t1.Origin(String) ^ t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer))` |
| **DC23** | `¬(t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer) ^ t0.OriginState(String) <> t1.OriginState(String))` |
| **DC24** | `¬(t0.OriginCityName(String) == t1.OriginCityName(String) ^ t0.OriginState(String) <> t1.OriginState(String))` |
| **DC25** | `¬(t0.OriginState(String) <> t1.OriginState(String) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC26** | `¬(t0.TailNum(String) == t1.TailNum(String) ^ t0.AirlineID(Integer) <> t1.AirlineID(Integer))` |
| **DC27** | `¬(t0.OriginStateName(String) == t1.OriginStateName(String) ^ t0.OriginState(String) <> t1.OriginState(String))` |
| **DC28** | `¬(t0.OriginState(String) == t1.OriginState(String) ^ t0.OriginStateName(String) <> t1.OriginStateName(String))` |
| **DC29** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginWac(Integer) == t1.OriginWac(Integer))` |
| **DC30** | `¬(t0.OriginStateFips(Integer) == t1.OriginStateFips(Integer) ^ t0.OriginWac(Integer) <> t1.OriginWac(Integer))` |
| **DC31** | `¬(t0.UniqueCarrier(String) == t1.UniqueCarrier(String) ^ t0.Carrier(String) <> t1.Carrier(String))` |
| **DC32** | `¬(t0.Carrier(String) == t1.Carrier(String) ^ t0.UniqueCarrier(String) <> t1.UniqueCarrier(String))` |
| **DC33** | `¬(t0.OriginCityName(String) <> t1.OriginCityName(String) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC34** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC35** | `¬(t0.OriginStateName(String) <> t1.OriginStateName(String) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC36** | `¬(t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer) ^ t0.OriginStateName(String) <> t1.OriginStateName(String))` |
| **DC37** | `¬(t0.FlightDate(String) == t1.FlightDate(String) ^ t0.DayofMonth(Integer) <> t1.DayofMonth(Integer))` |
| **DC38** | `¬(t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) < t1.OriginAirportSeqID(Integer))` |
| **DC39** | `¬(t0.OriginAirportSeqID(Integer) >= t1.OriginAirportSeqID(Integer) ^ t0.OriginAirportID(Integer) < t1.OriginAirportID(Integer))` |
| **DC40** | `¬(t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer) ^ t0.OriginStateName(String) <> t1.OriginStateName(String))` |
| **DC41** | `¬(t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer) ^ t0.OriginWac(Integer) <> t1.OriginWac(Integer))` |
| **DC42** | `¬(t0.OriginStateFips(Integer) == t1.OriginStateFips(Integer) ^ t0.OriginState(String) <> t1.OriginState(String))` |
| **DC43** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginState(String) == t1.OriginState(String))` |
| **DC44** | `¬(t0.OriginWac(Integer) <> t1.OriginWac(Integer) ^ t0.OriginCityName(String) == t1.OriginCityName(String))` |
| **DC45** | `¬(t0.TailNum(String) == t1.TailNum(String) ^ t0.UniqueCarrier(String) <> t1.UniqueCarrier(String))` |
| **DC46** | `¬(t0.DayofMonth(Integer) == t1.DayofMonth(Integer) ^ t0.Month(Integer) == t1.Month(Integer) ^ t0.FlightDate(String) <> t1.FlightDate(String))` |
| **DC47** | `¬(t0.OriginWac(Integer) <> t1.OriginWac(Integer) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC48** | `¬(t0.AirlineID(Integer) == t1.AirlineID(Integer) ^ t0.Carrier(String) <> t1.Carrier(String))` |
| **DC49** | `¬(t0.Carrier(String) == t1.Carrier(String) ^ t0.AirlineID(Integer) <> t1.AirlineID(Integer))` |
| **DC50** | `¬(t0.OriginStateName(String) <> t1.OriginStateName(String) ^ t0.OriginCityName(String) == t1.OriginCityName(String))` |
| **DC51** | `¬(t0.OriginAirportSeqID(Integer) <> t1.OriginAirportSeqID(Integer) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC52** | `¬(t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer) ^ t0.Origin(String) <> t1.Origin(String))` |
| **DC53** | `¬(t0.OriginWac(Integer) == t1.OriginWac(Integer) ^ t0.OriginState(String) <> t1.OriginState(String))` |
| **DC54** | `¬(t0.OriginState(String) == t1.OriginState(String) ^ t0.OriginWac(Integer) <> t1.OriginWac(Integer))` |
| **DC55** | `¬(t0.Quarter(Integer) <> t1.Quarter(Integer))`              |
| **DC56** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer))` |
| **DC57** | `¬(t0.OriginCityMarketID(Integer) <> t1.OriginCityMarketID(Integer) ^ t0.OriginAirportID(Integer) >= t1.OriginAirportID(Integer) ^ t0.OriginAirportSeqID(Integer) <= t1.OriginAirportSeqID(Integer))` |
| **DC58** | `¬(t0.TailNum(String) == t1.TailNum(String) ^ t0.Carrier(String) <> t1.Carrier(String))` |
| **DC59** | `¬(t0.OriginCityMarketID(Integer) <> t1.OriginCityMarketID(Integer) ^ t0.OriginCityName(String) == t1.OriginCityName(String))` |
| **DC60** | `¬(t0.OriginWac(Integer) <> t1.OriginWac(Integer) ^ t0.Origin(String) == t1.Origin(String))` |
| **DC61** | `¬(t0.OriginAirportSeqID(Integer) == t1.OriginAirportSeqID(Integer) ^ t0.OriginWac(Integer) <> t1.OriginWac(Integer))` |
| **DC62** | `¬(t0.Month(Integer) <> t1.Month(Integer) ^ t0.FlightDate(String) == t1.FlightDate(String))` |
| **DC63** | `¬(t0.OriginCityMarketID(Integer) <> t1.OriginCityMarketID(Integer) ^ t0.OriginAirportID(Integer) == t1.OriginAirportID(Integer))` |
| **DC64** | `¬(t0.AirlineID(Integer) == t1.AirlineID(Integer) ^ t0.UniqueCarrier(String) <> t1.UniqueCarrier(String))` |
| **DC65** | `¬(t0.UniqueCarrier(String) == t1.UniqueCarrier(String) ^ t0.AirlineID(Integer) <> t1.AirlineID(Integer))` |
| **DC66** | `¬(t0.OriginStateFips(Integer) <> t1.OriginStateFips(Integer) ^ t0.OriginCityName(String) == t1.OriginCityName(String))` |

### Strong Semantics (51 DCs)

| Category                                    | DC IDs                                                       | Interpretation                                               |
| ------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **BTS/DOT Airport & Geography Master Data** | **DC1, 3-8, 12, 15-21, 24-25, 27-28, 33-36, 42-44, 47, 50-52, 59-61, 63, 66** | **Airport/Location Master-Data Consistency**. These DCs express that BTS standardized airport geography fields should stay aligned: `OriginAirportID` / `OriginAirportSeqID` / `Origin` identify the same airport entity; `OriginCityMarketID`, `OriginCityName`, `OriginState`, `OriginStateName`, and `OriginStateFips` are descriptive or administrative attributes attached to that airport or market. In other words, the same airport/location should not map to conflicting city/state/market/geographic codes. |
| **Airport Identifier Co-Ordering**          | **DC38-39**                                                  | **Coordinated Airport Identifier Systems**. These two DCs can be interpreted as reflecting a stable co-ordering between `OriginAirportID` and `OriginAirportSeqID`, suggesting that the two identifier systems are not assigned independently but follow a broadly consistent master-data allocation scheme. Although weaker than direct equality-based mappings, they can still be packaged as structural regularities of the BTS airport registry. |
| **Calendar Determinism**                    | **DC9-10, 37, 46, 62**                                       | **Temporal Consistency in a Yearly Snapshot**. `FlightDate` deterministically fixes `DayOfWeek`, `Month`, and `DayofMonth`; conversely, in a yearly slice where `Year` is fixed, `Month + DayofMonth` also fixes `FlightDate` and `DayOfWeek`. These DCs capture immutable calendar structure or its snapshot version. |
| **Airline Identity Standards**              | **DC31-32, 48-49, 64-65**                                    | **Carrier Identity Alignment**. `AirlineID`, `UniqueCarrier`, and `Carrier` are different identifiers or namespaces for the operating airline. These DCs say that the same carrier identity should not correspond to conflicting alternative codes within the dataset snapshot. |
| **Operational Entity Snapshot**             | **DC26, 45, 58**                                             | **Tail Number to Operating Carrier Mapping**. `TailNum` identifies the aircraft, and in a given reporting snapshot it usually corresponds to a single operating carrier identity (`AirlineID` / `UniqueCarrier` / `Carrier`). This is best understood as a **snapshot-valid operational consistency** rule rather than a universal lifetime rule, but it is still semantically meaningful and presentable. |

### Weak Semantics (15 DCs)

| ID       | Denial Constraint Logic                                      | Explanation                                                  |
| -------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **DC2**  | `¬(t0.Year <> t1.Year)`                                      | **Single-Year Slice**. This is a dataset-selection artifact rather than a reusable domain rule. It says more about the extracted partition than about aviation semantics. |
| **DC11** | `¬(t0.OriginCityName <> t1.OriginCityName ^ t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID)` | **Wrapped Ordering Constraint**. Although `OriginCityName` is meaningful, the real driver is still the numeric relation between `AirportID` and `AirportSeqID`, so the semantic core is weak. |
| **DC13** | `¬(t0.OriginWac == t1.OriginWac ^ t0.OriginStateName <> t1.OriginStateName)` | **Broad Regional Code Alignment**. This can be read as a geographic consistency rule, but `OriginWac` is a coarser regional code and its alignment with state-level attributes is less direct than airport- or state-identifier mappings. |
| **DC14** | `¬(t0.OriginStateName == t1.OriginStateName ^ t0.OriginWac <> t1.OriginWac)` | Same as DC13. The relation is explainable, but weaker and less natural than direct airport-to-location or state-code-to-state-name mappings. |
| **DC22** | `¬(t0.Origin <> t1.Origin ^ t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID)` | This looks semantic on the surface because `Origin` appears, but the explanatory center is still the ordering relation between two identifier systems. |
| **DC23** | `¬(t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID ^ t0.OriginState <> t1.OriginState)` | Same pattern: a meaningful location attribute is attached to an essentially order-based artifact. Not ideal as a showcase DC. |
| **DC29** | `¬(t0.OriginStateFips <> t1.OriginStateFips ^ t0.OriginWac == t1.OriginWac)` | **Broad Regional Code Alignment**. This captures a regional-code regularity rather than a crisp administrative mapping. It is interpretable, but less semantically direct. |
| **DC30** | `¬(t0.OriginStateFips == t1.OriginStateFips ^ t0.OriginWac <> t1.OriginWac)` | Same as DC29. It is more of a dataset-level geographic regularity than a clean business rule. |
| **DC40** | `¬(t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID ^ t0.OriginStateName <> t1.OriginStateName)` | Same issue as DC23. The `StateName` part is interpretable, but the DC is still propped up by the ordering artifact. |
| **DC41** | `¬(t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID ^ t0.OriginWac <> t1.OriginWac)` | The geographic field is meaningful, but the ordering predicate weakens the overall semantic quality. |
| **DC53** | `¬(t0.OriginWac == t1.OriginWac ^ t0.OriginState <> t1.OriginState)` | **Broad Regional Code Alignment**. Again, this is a plausible geographic regularity, but the semantics are weaker than direct airport or state master-data rules. |
| **DC54** | `¬(t0.OriginState == t1.OriginState ^ t0.OriginWac <> t1.OriginWac)` | Same as DC53. It is explainable, but not among the strongest and cleanest location constraints. |
| **DC55** | `¬(t0.Quarter <> t1.Quarter)`                                | **Single-Quarter Slice**. This reflects the chosen snapshot, not a stable business dependency. |
| **DC56** | `¬(t0.OriginStateFips <> t1.OriginStateFips ^ t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID)` | **Geographic Field + ID Ordering Hybrid**. It can be read, but not very naturally. The constraint still depends on accidental monotonicity between identifiers. |
| **DC57** | `¬(t0.OriginCityMarketID <> t1.OriginCityMarketID ^ t0.OriginAirportID >= t1.OriginAirportID ^ t0.OriginAirportSeqID <= t1.OriginAirportSeqID)` | Same as DC56. `CityMarketID` is meaningful on its own, but once mixed with `>=` / `<=` over two ID systems, the rule becomes much less semantically clean. |

---

### 
